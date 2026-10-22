package com.hiasoon.heg.executor;

import com.hiasoon.heg.config.DataSourcePool;
import com.hiasoon.heg.entity.HegOperationRequest;
import com.hiasoon.heg.entity.HegOperationResponse;
import com.hiasoon.heg.entity.MongoOperation;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.UpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * MongoDB remote executor 鈥� command-handler registry pattern.
 *
 * Primary data source: {@code params} Map (JSON-deserialized LinkedHashMap).
 * Fallback: {@code mongoOp} structured object (JSON String fields 鈫� Bson Documents).
 *
 * Supports: FIND, FIND_ONE, COUNT, INSERT, INSERT_BATCH, SAVE,
 *           UPDATE, UPDATE_MULTI, DELETE, FIND_AND_MODIFY, FIND_AND_REMOVE,
 *           AGGREGATE, COLLECTION_EXISTS, DROP_COLLECTION.
 */
@Slf4j
@Component
public class MongoExecutor {

    private final DataSourcePool dataSourcePool;
    private final Map<String, BiFunction<MongoCollection<Document>, Map<String, Object>, Object>> handlers = new HashMap<>();

    public MongoExecutor(DataSourcePool dataSourcePool) {
        this.dataSourcePool = dataSourcePool;
        registerHandlers();
    }

    private void registerHandlers() {
        handlers.put("FIND",              this::handleFind);
        handlers.put("FIND_ONE",          this::handleFindOne);
        handlers.put("COUNT",             this::handleCount);
        handlers.put("INSERT",            this::handleInsert);
        handlers.put("INSERT_BATCH",      this::handleInsertBatch);
        handlers.put("SAVE",              this::handleSave);
        handlers.put("UPDATE",            this::handleUpdate);
        handlers.put("UPDATE_MULTI",      this::handleUpdateMulti);
        handlers.put("DELETE",            this::handleDelete);
        handlers.put("FIND_AND_MODIFY",   this::handleFindAndModify);
        handlers.put("FIND_AND_REMOVE",   this::handleFindAndRemove);
        handlers.put("AGGREGATE",         this::handleAggregate);
        handlers.put("COLLECTION_EXISTS", this::handleCollectionExists);
        handlers.put("DROP_COLLECTION",   this::handleDropCollection);
    }

    public HegOperationResponse execute(HegOperationRequest request) {
        String appId = request.getAppId();
        String opType = request.getOperationType();
        Map<String, Object> params = request.getParams();
        MongoOperation mongoOp = request.getMongoOp();

        String collectionName = stringParam(params, mongoOp, "collectionName");
        String databaseName = stringParam(params, mongoOp, "databaseName");
        if (databaseName == null || databaseName.isEmpty()) {
            databaseName = dataSourcePool.getMongoDefaultDatabase(appId);
            if (databaseName == null || databaseName.isEmpty()) {
                databaseName = "test";
            }
        }
        if (opType == null && mongoOp != null) {
            opType = mongoOp.getOperationType();
        }
        if (opType == null) {
            return HegOperationResponse.error(request.getRequestId(),
                    "INVALID_REQUEST", "Missing operationType");
        }
        if (collectionName == null || collectionName.isEmpty()) {
            return HegOperationResponse.error(request.getRequestId(),
                    "INVALID_REQUEST", "Missing collectionName");
        }

        com.mongodb.client.MongoClient client = dataSourcePool.getMongoClient(appId);
        if (client == null) {
            return HegOperationResponse.error(request.getRequestId(),
                    "MONGO_NOT_CONFIGURED", "MongoDB not configured: appId=" + appId);
        }

        try {
            MongoDatabase db = client.getDatabase(databaseName);
            String cmd = opType.toUpperCase();

            // COLLECTION_EXISTS / DROP_COLLECTION need database-level access
            if ("COLLECTION_EXISTS".equals(cmd)) {
                boolean exists = collectionReallyExists(db, collectionName);
                log.info("MongoDB OK: appId={}, cmd={}, collection={}, exists={}",
                        appId, cmd, collectionName, exists);
                return HegOperationResponse.success(request.getRequestId(), exists);
            }
            if ("DROP_COLLECTION".equals(cmd)) {
                db.getCollection(collectionName).drop();
                log.info("MongoDB OK: appId={}, cmd={}, collection={}", appId, cmd, collectionName);
                return HegOperationResponse.success(request.getRequestId(), "OK");
            }

            MongoCollection<Document> collection = db.getCollection(collectionName);
            BiFunction<MongoCollection<Document>, Map<String, Object>, Object> handler = handlers.get(cmd);
            if (handler == null) {
                return HegOperationResponse.error(request.getRequestId(),
                        "UNSUPPORTED_MONGO_COMMAND", "Unsupported: " + cmd);
            }
            Object result = handler.apply(collection, mergedParams(params, mongoOp));
            log.info("MongoDB OK: appId={}, cmd={}, collection={}, database={}",
                    appId, cmd, collectionName, databaseName);
            return HegOperationResponse.success(request.getRequestId(), result);
        } catch (Exception e) {
            log.error("MongoDB failed: cmd={}, collection={}, database={}",
                    opType, collectionName, databaseName, e);
            return HegOperationResponse.error(request.getRequestId(),
                    "MONGO_EXECUTION_ERROR", e.getMessage());
        }
    }

    // ---- command handlers ----

    private Object handleFind(MongoCollection<Document> coll, Map<String, Object> p) {
        Document query = safeDoc(p.get("query"));
        Document projection = safeDoc(p.get("projection"));
        Document sort = safeDoc(p.get("sort"));
        Integer skip = intParam(p, "skip");
        Integer limit = intParam(p, "limit");

        FindIterable<Document> iter = coll.find(query);
        if (!projection.isEmpty()) iter = iter.projection(projection);
        if (!sort.isEmpty()) iter = iter.sort(sort);
        if (skip != null && skip > 0) iter = iter.skip(skip);
        if (limit != null && limit > 0) iter = iter.limit(limit);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Document doc : iter) {
            results.add((Map<String, Object>) normalizeOut(doc));
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private Object handleFindOne(MongoCollection<Document> coll, Map<String, Object> p) {
        Document query = safeDoc(p.get("query"));
        Document projection = safeDoc(p.get("projection"));
        FindIterable<Document> iter = coll.find(query);
        if (!projection.isEmpty()) iter = iter.projection(projection);
        Document doc = iter.first();
        return doc != null ? normalizeOut(doc) : null;
    }

    private Object handleCount(MongoCollection<Document> coll, Map<String, Object> p) {
        return coll.countDocuments(safeDoc(p.get("query")));
    }

    private Object handleInsert(MongoCollection<Document> coll, Map<String, Object> p) {
        Document doc = safeDoc(p.get("document"));
        if (doc.isEmpty()) return 0L;
        coll.insertOne(doc);
        return normalizeOut(doc.get("_id"));
    }

    @SuppressWarnings("unchecked")
    private Object handleInsertBatch(MongoCollection<Document> coll, Map<String, Object> p) {
        List<Document> docs = safeDocList(p.get("documents"));
        if (docs.isEmpty()) return 0;
        coll.insertMany(docs);
        return docs.size();
    }

    private Object handleSave(MongoCollection<Document> coll, Map<String, Object> p) {
        Document doc = safeDoc(p.get("document"));
        if (doc.isEmpty()) return null;
        Object id = doc.get("_id");
        if (id != null) {
            UpdateResult ur = coll.replaceOne(new Document("_id", id), doc,
                    new com.mongodb.client.model.ReplaceOptions().upsert(true));
            return ur.getModifiedCount();
        } else {
            coll.insertOne(doc);
            return normalizeOut(doc.get("_id"));
        }
    }

    private Object handleUpdate(MongoCollection<Document> coll, Map<String, Object> p) {
        Document query = safeDoc(p.get("query"));
        if (query.isEmpty()) return 0L;
        return coll.updateOne(query, safeDoc(p.get("update"))).getModifiedCount();
    }

    private Object handleUpdateMulti(MongoCollection<Document> coll, Map<String, Object> p) {
        Document query = safeDoc(p.get("query"));
        if (query.isEmpty()) return 0L;
        return coll.updateMany(query, safeDoc(p.get("update"))).getModifiedCount();
    }

    private Object handleDelete(MongoCollection<Document> coll, Map<String, Object> p) {
        Document query = safeDoc(p.get("query"));
        if (query.isEmpty()) return 0L;
        return coll.deleteOne(query).getDeletedCount();
    }

    @SuppressWarnings("unchecked")
    private Object handleFindAndModify(MongoCollection<Document> coll, Map<String, Object> p) {
        Document query = safeDoc(p.get("query"));
        if (query.isEmpty()) return null;
        Document projection = safeDoc(p.get("projection"));
        FindOneAndUpdateOptions opts = new FindOneAndUpdateOptions()
                .returnDocument(ReturnDocument.AFTER).upsert(false);
        if (!projection.isEmpty()) opts.projection(projection);
        Document result = coll.findOneAndUpdate(query, safeDoc(p.get("update")), opts);
        return result != null ? normalizeOut(result) : null;
    }

    @SuppressWarnings("unchecked")
    private Object handleFindAndRemove(MongoCollection<Document> coll, Map<String, Object> p) {
        Document query = safeDoc(p.get("query"));
        if (query.isEmpty()) return null;
        Document result = coll.findOneAndDelete(query);
        return result != null ? normalizeOut(result) : null;
    }

    @SuppressWarnings("unchecked")
    private Object handleAggregate(MongoCollection<Document> coll, Map<String, Object> p) {
        List<Document> pipeline = safeDocList(p.get("aggregationPipeline"));
        if (pipeline.isEmpty()) pipeline = safeDocList(p.get("aggregation"));
        if (pipeline.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Document doc : coll.aggregate(pipeline)) {
            results.add((Map<String, Object>) normalizeOut(doc));
        }
        return results;
    }

    private Object handleCollectionExists(MongoCollection<Document> coll, Map<String, Object> p) {
        return true; // handled in execute() with database-level access
    }

    private Object handleDropCollection(MongoCollection<Document> coll, Map<String, Object> p) {
        coll.drop();
        return "OK";
    }

    private boolean collectionReallyExists(MongoDatabase db, String name) {
        for (String n : db.listCollectionNames()) {
            if (n.equals(name)) return true;
        }
        return false;
    }

    // ---- param helpers ----

    private Map<String, Object> mergedParams(Map<String, Object> params, MongoOperation mongoOp) {
        Map<String, Object> merged = new HashMap<>();
        if (params != null) merged.putAll(params);
        if (mongoOp != null) {
            putIf(merged, "collectionName", mongoOp.getCollectionName());
            putIf(merged, "databaseName", mongoOp.getDatabaseName());
            putIf(merged, "query", mongoOp.getQuery());
            putIf(merged, "projection", mongoOp.getProjection());
            putIf(merged, "sort", mongoOp.getSort());
            putIf(merged, "update", mongoOp.getUpdateOperators());
            putIf(merged, "document", mongoOp.getDocument());
            putIf(merged, "aggregationPipeline", mongoOp.getAggregationPipeline());
            putIf(merged, "skip", mongoOp.getSkip());
            putIf(merged, "limit", mongoOp.getLimit());
            if (mongoOp.getOptions() != null) merged.put("options", mongoOp.getOptions());
        }
        return merged;
    }

    private void putIf(Map<String, Object> map, String key, Object value) {
        if (value != null) map.putIfAbsent(key, value);
    }

    private String stringParam(Map<String, Object> params, MongoOperation mongoOp, String key) {
        if (params != null && params.containsKey(key)) {
            Object v = params.get(key);
            return v != null ? v.toString() : null;
        }
        if (mongoOp != null) {
            switch (key) {
                case "collectionName": return mongoOp.getCollectionName();
                case "databaseName":   return mongoOp.getDatabaseName();
                default: return null;
            }
        }
        return null;
    }

    private Integer intParam(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    // ---- Bson conversion (Map objects + JSON strings) ----

    @SuppressWarnings("unchecked")
    private Document safeDoc(Object obj) {
        Document doc;
        if (obj == null) {
            doc = new Document();
        } else if (obj instanceof Map) {
            doc = new Document((Map<String, Object>) obj);
        } else if (obj instanceof String && !((String) obj).isEmpty()) {
            doc = Document.parse((String) obj);
        } else {
            doc = new Document();
        }
        return (Document) bsonizeIn(doc);
    }

    @SuppressWarnings("unchecked")
    private List<Document> safeDocList(Object obj) {
        List<Document> list = new ArrayList<>();
        if (obj instanceof List) {
            for (Object item : (List<?>) obj) {
                Document doc;
                if (item instanceof Map) {
                    doc = new Document((Map<String, Object>) item);
                } else if (item instanceof String && !((String) item).isEmpty()) {
                    doc = Document.parse((String) item);
                } else {
                    continue;
                }
                list.add((Document) bsonizeIn(doc));
            }
        }
        return list;
    }

    // ---- ObjectId normalization ----

    private static final Pattern HEX_OBJECT_ID = Pattern.compile("^[0-9a-fA-F]{24}$");

    private static boolean isHexObjectId(String value) {
        return value != null && HEX_OBJECT_ID.matcher(value).matches();
    }

    /**
     * 出参归一化：递归把 BSON ObjectId 转成 hex 字符串，避免 JSON 序列化时
     * 被展开成 {"date":...,"timestamp":...} 导致业务端 String 类型 id 错乱。
     */
    @SuppressWarnings("unchecked")
    private Object normalizeOut(Object value) {
        if (value instanceof ObjectId) {
            return ((ObjectId) value).toHexString();
        }
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                copy.put(entry.getKey(), normalizeOut(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(normalizeOut(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * 入参归一化：递归把扩展 JSON 的 {"$oid":"hex"} 以及 _id 字段下的
     * 24 位 hex 字符串转成 ObjectId，保证按 id 查询/删除/更新能匹配库里的 ObjectId。
     */
    @SuppressWarnings("unchecked")
    private Object bsonizeIn(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            Object oid = map.get("$oid");
            if (map.size() == 1 && oid instanceof String && isHexObjectId((String) oid)) {
                return new ObjectId((String) oid);
            }
            Document doc = new Document();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object converted = bsonizeIn(entry.getValue());
                if ("_id".equals(key)) {
                    converted = bsonizeIdValue(converted);
                }
                doc.put(key, converted);
            }
            return doc;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(bsonizeIn(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * 处理 _id 字段的值：hex 字符串转 ObjectId；
     * 列表（$in/$nin）与操作符 Map（$ne/$gt 等）内的 hex 同样转换。
     */
    @SuppressWarnings("unchecked")
    private Object bsonizeIdValue(Object value) {
        if (value instanceof String && isHexObjectId((String) value)) {
            return new ObjectId((String) value);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(bsonizeIdValue(item));
            }
            return copy;
        }
        if (value instanceof Map) {
            // 操作符 Map（如 {"$ne":"hex"}、{"$in":[...]}），递归转换其中的 hex 值；
            // {"$oid":"hex"} 形式已在 bsonizeIn 中先于本方法被转成 ObjectId。
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                copy.put(entry.getKey(), bsonizeIdValue(entry.getValue()));
            }
            return copy;
        }
        return value;
    }
}
