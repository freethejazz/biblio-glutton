package com.scienceminer.glutton.storage.lookup;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.scienceminer.glutton.configuration.LookupConfiguration;
import com.scienceminer.glutton.data.MatchingDocument;
import com.scienceminer.glutton.data.Biblio;
import com.scienceminer.glutton.harvester.HALOAIPMHHarvester;
import com.scienceminer.glutton.harvester.HALAPIHarvester;
import com.scienceminer.glutton.exception.ServiceException;
import com.scienceminer.glutton.exception.ServiceOverloadedException;
import com.scienceminer.glutton.serialization.BiblioSerializer;
import com.scienceminer.glutton.storage.StorageEnvFactory;
import com.scienceminer.glutton.indexing.ElasticSearchIndexer;
import com.scienceminer.glutton.indexing.ElasticSearchAsyncIndexer;
import com.scienceminer.glutton.utils.BinarySerialiser;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.lmdbjava.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDateTime;

import static com.scienceminer.glutton.web.resource.DataController.DEFAULT_MAX_SIZE_LIST;
import static java.nio.ByteBuffer.allocateDirect;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.lowerCase;

/**
 * Warning: this is HAL metadata
 * Singleton class
 * Lookup hal id -> metadata 
 * Lookup doi -> hal id
 */
public class HALLookup {
    private static final Logger LOGGER = LoggerFactory.getLogger(HALLookup.class);

    private static volatile HALLookup instance;

    private Env<ByteBuffer> environment;
    private Dbi<ByteBuffer> dbHALJson;
    private Dbi<ByteBuffer> dbDoiToHal;

    public static final String ENV_NAME = "hal";

    public static final String NAME_HAL_JSON = ENV_NAME + "_Jsondoc";
    public static final String NAME_DOI2HAL = ENV_NAME + "_doi2hal";

    private final int batchStoringSize;
    private final int batchIndexingSize;

    private LookupConfiguration configuration;

    // this date keeps track of the latest indexed date of the metadata database
    private LocalDateTime lastIndexed = null; 

    public static HALLookup getInstance(StorageEnvFactory storageEnvFactory) {
        if (instance == null) {
            synchronized (HALLookup.class) {
                if (instance == null) {
                    getNewInstance(storageEnvFactory);
                }
            }
        }
        return instance;
    }

    /**
     * Creates a new instance.
     */
    private static synchronized void getNewInstance(StorageEnvFactory storageEnvFactory) {
        instance = new HALLookup(storageEnvFactory);
    }

    private HALLookup(StorageEnvFactory storageEnvFactory) {
        this.environment = storageEnvFactory.getEnv(ENV_NAME);

        configuration = storageEnvFactory.getConfiguration();
        batchStoringSize = configuration.getStoringBatchSize();
        batchIndexingSize = configuration.getIndexingBatchSize();

        dbHALJson = this.environment.openDbi(NAME_HAL_JSON, DbiFlags.MDB_CREATE);
        dbDoiToHal = this.environment.openDbi(NAME_DOI2HAL, DbiFlags.MDB_CREATE);
    }

    public void loadFromHALAPI(Meter meterValidRecord, 
                            Counter counterInvalidRecords, 
                            Counter counterIndexedRecords, 
                            Counter counterFailedIndexedRecords) {
        final TransactionWrapper transactionWrapper = new TransactionWrapper(environment.txnWrite());
        final AtomicInteger counter = new AtomicInteger(0);

        HALAPIHarvester harvester = new HALAPIHarvester(transactionWrapper);
        harvester.fetchAllDocuments(this, meterValidRecord, counterInvalidRecords, 
            counterIndexedRecords, counterFailedIndexedRecords);
        ElasticSearchIndexer.getInstance(configuration).refreshIndex(configuration.getElastic().getIndex());
    }

    @Deprecated
    public void loadFromOAIPMH(Meter meterValidRecord, Counter counterInvalidRecords) {
        final TransactionWrapper transactionWrapper = new TransactionWrapper(environment.txnWrite());
        final AtomicInteger counter = new AtomicInteger(0);

        HALOAIPMHHarvester harvester = new HALOAIPMHHarvester(transactionWrapper);
        harvester.fetchAllDocuments(this, meterValidRecord, counterInvalidRecords);
    }

    public void storeObject(Biblio biblio, Txn<ByteBuffer> tx) {
        try {
            String dbBiblioJson = BiblioSerializer.serializeJson(biblio, null, this);
//System.out.println(dbBiblioJson);
            store(lowerCase(biblio.getHalId()), dbBiblioJson, dbHALJson, tx);
            if (!isBlank(biblio.getDoi()))
                storeNoCompression(lowerCase(biblio.getDoi()), lowerCase(biblio.getHalId()), dbDoiToHal, tx);
        } catch (Exception e) {
            LOGGER.error("Cannot serialize the metadata", e);
        }
    }

    private void store(String key, String value, Dbi<ByteBuffer> db, Txn<ByteBuffer> tx) {
        try {
            final ByteBuffer keyBuffer = allocateDirect(environment.getMaxKeySize());
            keyBuffer.put(BinarySerialiser.serialize(key)).flip();
            final byte[] serializedValue = BinarySerialiser.serializeAndCompress(value);
            final ByteBuffer valBuffer = allocateDirect(serializedValue.length);
            valBuffer.put(serializedValue).flip();
            db.put(tx, keyBuffer, valBuffer);
        } catch (Exception e) {
            LOGGER.error("Cannot store the entry " + key, e);
        }
    }

    private void storeNoCompression(String key, String value, Dbi<ByteBuffer> db, Txn<ByteBuffer> tx) {
        try {
            final ByteBuffer keyBuffer = allocateDirect(environment.getMaxKeySize());
            keyBuffer.put(BinarySerialiser.serialize(key)).flip();
            final byte[] serializedValue = BinarySerialiser.serialize(value);
            final ByteBuffer valBuffer = allocateDirect(serializedValue.length);
            valBuffer.put(serializedValue).flip();
            db.put(tx, keyBuffer, valBuffer);
        } catch (Exception e) {
            LOGGER.error("Cannot store the entry " + key, e);
        }
    }

    public void commitTransactions(TransactionWrapper transactionWrapper) {       
        transactionWrapper.tx.commit();
        transactionWrapper.tx.close();
        transactionWrapper.tx = environment.txnWrite();
    }

    public Map<String, Long> getSize() {

        Map<String, Long> sizes = new HashMap<>();
        try (final Txn<ByteBuffer> txn = this.environment.txnRead()) {
            sizes.put(NAME_HAL_JSON, dbHALJson.stat(txn).entries);
        } catch (Env.ReadersFullException e) {
            throw new ServiceOverloadedException("Not enough readers for LMDB access, increase them or reduce the parallel request rate. ", e);
        }

        return sizes;
    }

    public Long getFullSize() {
        long fullsize = 0;
        Map<String, Long> sizes = getSize();
        for (Map.Entry<String, Long> entry : sizes.entrySet()) {
            fullsize += entry.getValue();
        }
        return fullsize;
    }

    public String retrieveJsonDocument(String halID) {
        final ByteBuffer keyBuffer = allocateDirect(environment.getMaxKeySize());
        ByteBuffer cachedData = null;
        String theRecord = null;
        try (Txn<ByteBuffer> tx = environment.txnRead()) {
            keyBuffer.put(BinarySerialiser.serialize(lowerCase(halID))).flip();
            cachedData = dbHALJson.get(tx, keyBuffer);
            if (cachedData != null) {
                theRecord = (String) BinarySerialiser.deserializeAndDecompress(cachedData);
            }
        } catch (Env.ReadersFullException e) {
            throw new ServiceOverloadedException("Not enough readers for LMDB access, increase them or reduce the parallel request rate. ", e);
        } catch (Exception e) {
            LOGGER.error("Cannot retrieve HAL metadata by HAL ID:  " + halID, e);
        }

        return theRecord;
    }

    /**
     * Lookup by HAL ID
     **/
    public MatchingDocument retrieveByHalId(String halID) {
        if (isBlank(halID)) {
            throw new ServiceException(400, "The supplied HAL ID is null.");
        }
        final String jsonDocument = retrieveJsonDocument(lowerCase(halID));

        return new MatchingDocument("hal:"+halID, jsonDocument);
    }

    public String retrieveHalIdByDoi(String doi) {
        final ByteBuffer keyBuffer = allocateDirect(environment.getMaxKeySize());
        ByteBuffer cachedData = null;
        String halId = null;
        try (Txn<ByteBuffer> tx = environment.txnRead()) {
            keyBuffer.put(BinarySerialiser.serialize(lowerCase(doi))).flip();
            cachedData = dbDoiToHal.get(tx, keyBuffer);
            if (cachedData != null) {
                halId = (String) BinarySerialiser.deserialize(cachedData);
            }
        } catch (Env.ReadersFullException e) {
            throw new ServiceOverloadedException("Not enough readers for LMDB access, increase them or reduce the parallel request rate. ", e);
        } catch (Exception e) {
            LOGGER.error("Cannot retrieve HAL ID by DOI: " + doi, e);
        }

        return halId;
    }

    public List<Pair<String, String>> retrieveList(Integer total) {
        return retrieveList(total, dbHALJson);
    }

    public List<Pair<String, String>> retrieveList(Integer total, Dbi<ByteBuffer> db) {
        if (total == null || total == 0) {
            total = DEFAULT_MAX_SIZE_LIST;
        }

        List<Pair<String, String>> values = new ArrayList<>();
        int counter = 0;

        try (Txn<ByteBuffer> txn = environment.txnRead()) {
            try (CursorIterable<ByteBuffer> it = db.iterate(txn, KeyRange.all())) {
                for (final CursorIterable.KeyVal<ByteBuffer> kv : it) {
                    String key = null;
                    try {
                        key = (String) BinarySerialiser.deserialize(kv.key());
                        values.add(new ImmutablePair<>(key, (String) BinarySerialiser.deserializeAndDecompress(kv.val())));
                    } catch (IOException e) {
                        LOGGER.error("Cannot decompress document with key: " + key, e);
                    }
                    if (counter == total) {
                        txn.close();
                        break;
                    }
                    counter++;
                }
            }
        } catch (Env.ReadersFullException e) {
            throw new ServiceOverloadedException("Not enough readers for LMDB access, increase them or reduce the parallel request rate. ", e);
        }

        return values;
    }

    public synchronized LocalDateTime getLastIndexed() {
        if (lastIndexed != null)
            return lastIndexed;
        else {
            // get a possible value made persistent in the db
            final ByteBuffer keyBuffer = allocateDirect(environment.getMaxKeySize());
            ByteBuffer cachedData = null;
            try (Txn<ByteBuffer> tx = environment.txnRead()) {
                keyBuffer.put(BinarySerialiser.serialize("last-indexed-date")).flip();
                cachedData = dbHALJson.get(tx, keyBuffer);
                if (cachedData != null) {
                    lastIndexed = (LocalDateTime) BinarySerialiser.deserializeAndDecompress(cachedData);
                }
            } catch (Env.ReadersFullException e) {
                throw new ServiceOverloadedException("Not enough readers for LMDB access, increase them or reduce the parallel request rate. ", e);
            } catch (Exception e) {
                LOGGER.error("Cannot retrieve the persistent last indexed date object", e);
            }
            return lastIndexed;
        }
    }

    public synchronized void setLastIndexed(LocalDateTime lastIndexed) {
        this.lastIndexed = lastIndexed;

        // persistent store of this date
        final TransactionWrapper transactionWrapper = new TransactionWrapper(environment.txnWrite());
        try {
            final ByteBuffer keyBuffer = allocateDirect(environment.getMaxKeySize());
            keyBuffer.put(BinarySerialiser.serialize("last-indexed-date")).flip();
            final byte[] serializedValue = BinarySerialiser.serializeAndCompress(this.lastIndexed);
            final ByteBuffer valBuffer = allocateDirect(serializedValue.length);
            valBuffer.put(serializedValue).flip();
            dbHALJson.put(transactionWrapper.tx, keyBuffer, valBuffer);
        } catch (Exception e) {
            LOGGER.error("Cannot store the last-indexed-date");
        } finally {
            transactionWrapper.tx.commit();
            transactionWrapper.tx.close();
        }
    }

    public void indexMetadata(ElasticSearchIndexer indexer, Meter meter, Counter counterIndexedRecords) {
        ElasticSearchIndexer.getInstance(configuration)
            .indexCollection(environment, dbHALJson, true, meter, counterIndexedRecords);
    }

    public void indexDocuments(List<Biblio> documents, boolean update, Counter counterIndexedRecords, Counter counterFailedIndexedRecords) {
        List<String> jsonRecords = new ArrayList<>();
        for(Biblio document : documents) {
            // convert Biblio object into crossref json format
            try{
                jsonRecords.add(BiblioSerializer.serializeJson(document, null, this));
            } catch (Exception e) {
            LOGGER.error("Cannot serialize the metadata", e);
        }
        }
        ElasticSearchAsyncIndexer.getInstance(configuration)
            .asyncIndexDocuments(jsonRecords, update, counterIndexedRecords, counterFailedIndexedRecords);
    }

    public int getStoringBatchSize() {
        return this.batchStoringSize;
    }

    public int getIndexingBatchSize() {
        return this.batchIndexingSize;
    }

    public void close() {
        this.environment.close();
    }
}
