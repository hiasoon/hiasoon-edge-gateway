package com.hiasoon.heg.executor;

import com.hiasoon.heg.config.DataSourcePool;
import com.hiasoon.heg.entity.HegOperationRequest;
import com.hiasoon.heg.entity.HegOperationResponse;
import com.hiasoon.heg.entity.RedisOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.ZParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.params.ZAddParams;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis remote command executor (Jedis 3.x compatible).
 *
 * Uses a CommandHandler registry for true generality.
 * Adding a new command only requires one line in registerHandlers().
 */
@Slf4j
@Component
public class RedisExecutor {

    private final DataSourcePool dataSourcePool;
    private final Map<String, CommandHandler> handlers = new HashMap<>();

    public RedisExecutor(DataSourcePool dataSourcePool) {
        this.dataSourcePool = dataSourcePool;
        registerHandlers();
    }
    private void registerHandlers() {
        // String / Value
        handlers.put("GET",        (j, o) -> j.get(o.getKey()));
        handlers.put("SET",        this::handleSet);
        handlers.put("SETEX",      (j, o) -> j.setex(o.getKey(), safeInt(o.getExpireSeconds()), serializeValue(o.getValue())));
        handlers.put("SETNX",      (j, o) -> j.setnx(o.getKey(), serializeValue(o.getValue())));
        handlers.put("SETRANGE",   (j, o) -> j.setrange(o.getKey(), longParam(o.getExtraParams(), "offset"), serializeValue(o.getValue())));
        handlers.put("STRLEN",     (j, o) -> j.strlen(o.getKey()));
        handlers.put("SETBIT",     (j, o) -> j.setbit(o.getKey(), longParam(o.getExtraParams(), "offset"), boolParam(o.getExtraParams(), "bitValue")));
        handlers.put("GETBIT",     (j, o) -> j.getbit(o.getKey(), longParam(o.getExtraParams(), "offset")));
        handlers.put("INCR",       (j, o) -> j.incr(o.getKey()));
        handlers.put("DECR",       (j, o) -> j.decr(o.getKey()));
        handlers.put("INCRBY",     (j, o) -> j.incrBy(o.getKey(), safeLong(o.getValue())));
        handlers.put("INCRBYFLOAT",(j, o) -> j.incrByFloat(o.getKey(), safeDouble(o.getValue())));
        handlers.put("APPEND",     (j, o) -> j.append(o.getKey(), serializeValue(o.getValue())));
        handlers.put("GETSET",     (j, o) -> j.getSet(o.getKey(), serializeValue(o.getValue())));
        handlers.put("MGET",       (j, o) -> j.mget(strArrayParam(o.getExtraParams(), "keys")));
        handlers.put("GETRANGE",   (j, o) -> j.getrange(o.getKey(), longParam(o.getExtraParams(), "start"), longParam(o.getExtraParams(), "end")));

        // Hash
        handlers.put("HGET",       (j, o) -> j.hget(o.getKey(), field(o)));
        handlers.put("HSET",       (j, o) -> j.hset(o.getKey(), field(o), serializeValue(o.getValue())));
        handlers.put("HDEL",       (j, o) -> j.hdel(o.getKey(), field(o)));
        handlers.put("HGETALL",    (j, o) -> j.hgetAll(o.getKey()));
        handlers.put("HEXISTS",    (j, o) -> j.hexists(o.getKey(), field(o)));
        handlers.put("HMGET",      (j, o) -> j.hmget(o.getKey(), strArrayParam(o.getExtraParams(), "fields")));
        handlers.put("HKEYS",      (j, o) -> j.hkeys(o.getKey()));
        handlers.put("HVALS",      (j, o) -> j.hvals(o.getKey()));
        handlers.put("HLEN",       (j, o) -> j.hlen(o.getKey()));
        handlers.put("HMSET",      (j, o) -> j.hmset(o.getKey(), strMapParam(o.getExtraParams(), "hashMap")));
        handlers.put("HSETNX",     (j, o) -> j.hsetnx(o.getKey(), field(o), serializeValue(o.getValue())));
        handlers.put("HINCRBY",    (j, o) -> j.hincrBy(o.getKey(), field(o), safeLong(o.getValue())));
        handlers.put("HINCRBYFLOAT", (j, o) -> j.hincrByFloat(o.getKey(), field(o), safeDouble(o.getValue())));

        // List
        handlers.put("LPUSH",      this::handleLPush);
        handlers.put("RPUSH",      this::handleRPush);
        handlers.put("LPOP",       (j, o) -> j.lpop(o.getKey()));
        handlers.put("RPOP",       (j, o) -> j.rpop(o.getKey()));
        handlers.put("LRANGE",     (j, o) -> j.lrange(o.getKey(), longParam(o.getExtraParams(), "start"), longParam(o.getExtraParams(), "end")));
        handlers.put("LLEN",       (j, o) -> j.llen(o.getKey()));
        handlers.put("BLPOP",      (j, o) -> j.blpop(intParam(o.getExtraParams(), "timeout"), o.getKey()));
        handlers.put("BRPOP",      (j, o) -> j.brpop(intParam(o.getExtraParams(), "timeout"), o.getKey()));
        handlers.put("RPOPLPUSH",  this::handleRPopLPush);
        handlers.put("BRPOPLPUSH", (j, o) -> j.brpoplpush(o.getKey(), strParam(o.getExtraParams(), "destinationKey"), intParam(o.getExtraParams(), "timeout")));
        handlers.put("LPUSHX",     (j, o) -> j.lpushx(o.getKey(), serializeValues(o)));
        handlers.put("LINSERT",    this::handleLInsert);
        handlers.put("RPUSHX",     (j, o) -> j.rpushx(o.getKey(), serializeValues(o)));
        handlers.put("LSET",       (j, o) -> j.lset(o.getKey(), longParam(o.getExtraParams(), "index"), serializeValue(o.getValue())));
        handlers.put("LREM",       (j, o) -> j.lrem(o.getKey(), longParam(o.getExtraParams(), "count"), serializeValue(o.getValue())));
        handlers.put("LINDEX",     (j, o) -> j.lindex(o.getKey(), longParam(o.getExtraParams(), "index")));
        handlers.put("LTRIM",      (j, o) -> j.ltrim(o.getKey(), longParam(o.getExtraParams(), "start"), longParam(o.getExtraParams(), "end")));

        // Set
        handlers.put("SADD",       (j, o) -> j.sadd(o.getKey(), serializeValues(o)));
        handlers.put("SREM",       (j, o) -> j.srem(o.getKey(), serializeValues(o)));
        handlers.put("SMEMBERS",   (j, o) -> j.smembers(o.getKey()));
        handlers.put("SISMEMBER",  (j, o) -> j.sismember(o.getKey(), serializeValue(o.getValue())));
        handlers.put("SCARD",      (j, o) -> j.scard(o.getKey()));
        handlers.put("SPOP",       (j, o) -> j.spop(o.getKey()));
        handlers.put("SRANDMEMBER", (j, o) -> j.srandmember(o.getKey()));
        handlers.put("SINTER",     this::handleSInter);
        handlers.put("SINTERSTORE", this::handleSInterStore);
        handlers.put("SUNION",     this::handleSUnion);
        handlers.put("SUNIONSTORE", this::handleSUnionStore);
        handlers.put("SDIFF",      this::handleSDiff);
        handlers.put("SDIFFSTORE", this::handleSDiffStore);

        // ZSet (Jedis 3.x core commands only)
        handlers.put("ZADD",       this::handleZAdd);
        handlers.put("ZREM",       (j, o) -> j.zrem(o.getKey(), serializeValues(o)));
        handlers.put("ZRANGE",     this::handleZRange);
        handlers.put("ZREVRANGE",  this::handleZRevRange);
        handlers.put("ZRANGEBYSCORE", this::handleZRangeByScore);
        handlers.put("ZREVRANGEBYSCORE", this::handleZRevRangeByScore);
        handlers.put("ZRANK",      (j, o) -> j.zrank(o.getKey(), serializeValue(o.getValue())));
        handlers.put("ZREVRANK",   (j, o) -> j.zrevrank(o.getKey(), serializeValue(o.getValue())));
        handlers.put("ZSCORE",     (j, o) -> j.zscore(o.getKey(), serializeValue(o.getValue())));
        handlers.put("ZCARD",      (j, o) -> j.zcard(o.getKey()));
        handlers.put("ZCOUNT",     (j, o) -> j.zcount(o.getKey(), doubleParam(o.getExtraParams(), "min"), doubleParam(o.getExtraParams(), "max")));
        handlers.put("ZINCRBY",    (j, o) -> j.zincrby(o.getKey(), doubleParam(o.getExtraParams(), "delta"), serializeValue(o.getValue())));
        handlers.put("ZREMRANGEBYRANK", (j, o) -> j.zremrangeByRank(o.getKey(), longParam(o.getExtraParams(), "start"), longParam(o.getExtraParams(), "end")));
        handlers.put("ZREMRANGEBYSCORE", (j, o) -> j.zremrangeByScore(o.getKey(), doubleParam(o.getExtraParams(), "min"), doubleParam(o.getExtraParams(), "max")));
        handlers.put("ZREMRANGEBYLEX", (j, o) -> j.zremrangeByLex(o.getKey(), strParam(o.getExtraParams(), "min"), strParam(o.getExtraParams(), "max")));
        handlers.put("ZLEXCOUNT",  (j, o) -> j.zlexcount(o.getKey(), strParam(o.getExtraParams(), "min"), strParam(o.getExtraParams(), "max")));
        handlers.put("ZUNIONSTORE", this::handleZUnionStore);
        handlers.put("ZINTERSTORE", this::handleZInterStore);
        handlers.put("ZRANGEBYLEX", this::handleZRangeByLex);
        handlers.put("ZREVRANGEBYLEX", this::handleZRevRangeByLex);

        // Key
        handlers.put("DEL",        this::handleDel);
        handlers.put("EXPIRE",     (j, o) -> j.expire(o.getKey(), safeLong(o.getExpireSeconds())));
        handlers.put("TTL",        (j, o) -> j.ttl(o.getKey()));
        handlers.put("EXISTS",     (j, o) -> j.exists(o.getKey()));
        handlers.put("KEYS",       (j, o) -> j.keys(o.getKey()));
        handlers.put("EXPIREAT",   (j, o) -> j.expireAt(o.getKey(), safeLong(o.getValue())));
        handlers.put("PERSIST",    (j, o) -> j.persist(o.getKey()));

        // Pub/Sub
        handlers.put("PUBLISH",    (j, o) -> j.publish(strParam(o.getExtraParams(), "channel"), serializeValue(o.getValue())));
    }
    // ==================== Core Dispatch ====================

    public HegOperationResponse execute(HegOperationRequest request) {
        String appId = request.getAppId();
        RedisOperation redisOp = request.getRedisOp();

        JedisPool pool = dataSourcePool.getRedisPool(appId);
        if (pool == null) {
            return HegOperationResponse.error(request.getRequestId(),
                    "REDIS_NOT_CONFIGURED", "Redis data source not configured: appId=" + appId);
        }

        String command = request.getOperationType();
        if (command == null && redisOp != null) {
            command = redisOp.getCommand();
        }
        if (command == null) {
            return HegOperationResponse.error(request.getRequestId(),
                    "MISSING_COMMAND", "Command name is null");
        }

        CommandHandler handler = handlers.get(command.toUpperCase());
        if (handler == null) {
            log.error("Unsupported Redis command: {}", command);
            return HegOperationResponse.error(request.getRequestId(),
                    "UNSUPPORTED_REDIS_COMMAND", "Unsupported Redis command: " + command);
        }

        try (Jedis jedis = pool.getResource()) {
            Object result = handler.execute(jedis, redisOp);
            applyPostExpire(jedis, redisOp);
            return HegOperationResponse.success(request.getRequestId(), result);
        } catch (Exception e) {
            log.error("Redis execution failed: command={}, key={}", command,
                    redisOp != null ? redisOp.getKey() : null, e);
            return HegOperationResponse.error(request.getRequestId(),
                    "REDIS_EXECUTION_ERROR", e.getMessage());
        }
    }

    @FunctionalInterface
    interface CommandHandler {
        Object execute(Jedis jedis, RedisOperation op) throws Exception;
    }

    // ==================== SET Handler ====================

    private Object handleSet(Jedis jedis, RedisOperation op) {
        String key = op.getKey();
        String value = serializeValue(op.getValue());
        Map<String, Object> extra = op.getExtraParams();
        SetParams params = SetParams.setParams();
        if (extra != null) {
            if (boolParam(extra, "nx")) params.nx();
            if (boolParam(extra, "xx")) params.xx();
            if (extra.get("ex") instanceof Number) params.ex(((Number) extra.get("ex")).intValue());
        }
        return jedis.set(key, value, params);
    }

    // ==================== List Handlers ====================

    private Object handleLPush(Jedis jedis, RedisOperation op) {
        return jedis.lpush(op.getKey(), serializeValues(op));
    }

    private Object handleRPush(Jedis jedis, RedisOperation op) {
        return jedis.rpush(op.getKey(), serializeValues(op));
    }

    private Object handleRPopLPush(Jedis jedis, RedisOperation op) {
        return jedis.rpoplpush(op.getKey(), String.valueOf(op.getValue()));
    }

    private Object handleLInsert(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        boolean before = e != null && !boolParam(e, "before");
        String direction = before ? "BEFORE" : "AFTER";
        String pivot = serializeValue(e != null ? e.get("pivot") : null);
        String value = serializeValue(op.getValue());
        // Use Lua eval to avoid BinaryClient.LIST_POSITION enum incompatibility
        return jedis.eval("return redis.call('LINSERT', KEYS[1], ARGV[1], ARGV[2], ARGV[3])",
                java.util.Collections.singletonList(op.getKey()),
                java.util.Arrays.asList(direction, pivot, value));
    }

    // ==================== Set Handlers ====================

    private Object handleSInter(Jedis jedis, RedisOperation op) {
        return jedis.sinter(prependKey(op.getKey(), strArrayParam(op.getExtraParams(), "otherKeys")));
    }

    private Object handleSInterStore(Jedis jedis, RedisOperation op) {
        return jedis.sinterstore(strParam(op.getExtraParams(), "destKey"),
                prependKey(op.getKey(), strArrayParam(op.getExtraParams(), "otherKeys")));
    }

    private Object handleSUnion(Jedis jedis, RedisOperation op) {
        return jedis.sunion(prependKey(op.getKey(), strArrayParam(op.getExtraParams(), "otherKeys")));
    }

    private Object handleSUnionStore(Jedis jedis, RedisOperation op) {
        return jedis.sunionstore(strParam(op.getExtraParams(), "destKey"),
                prependKey(op.getKey(), strArrayParam(op.getExtraParams(), "otherKeys")));
    }

    private Object handleSDiff(Jedis jedis, RedisOperation op) {
        return jedis.sdiff(prependKey(op.getKey(), strArrayParam(op.getExtraParams(), "otherKeys")));
    }

    private Object handleSDiffStore(Jedis jedis, RedisOperation op) {
        return jedis.sdiffstore(strParam(op.getExtraParams(), "destKey"),
                prependKey(op.getKey(), strArrayParam(op.getExtraParams(), "otherKeys")));
    }

    private String[] prependKey(String key, String[] otherKeys) {
        String[] all = new String[otherKeys.length + 1];
        all[0] = key;
        System.arraycopy(otherKeys, 0, all, 1, otherKeys.length);
        return all;
    }

    // ==================== ZSet Handlers ====================

    private Object handleZAdd(Jedis jedis, RedisOperation op) {
        Map<String, Object> extra = op.getExtraParams();
        ZAddParams params = ZAddParams.zAddParams();
        if (extra != null && boolParam(extra, "nx")) params.nx();

        if (extra != null && extra.get("tuples") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tuples = (List<Map<String, Object>>) extra.get("tuples");
            Map<String, Double> scoreMembers = new LinkedHashMap<>();
            for (Map<String, Object> t : tuples) {
                scoreMembers.put(serializeValue(t.get("value")), safeDouble(t.get("score")));
            }
            return jedis.zadd(op.getKey(), scoreMembers, params);
        }
        double score = doubleParam(extra, "score");
        return jedis.zadd(op.getKey(), score, serializeValue(op.getValue()), params);
    }

    private Object handleZRange(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        long start = longParam(e, "start");
        long end = longParam(e, "end");
        if (e != null && boolParam(e, "withScores")) {
            return tupleSetToMaps(jedis.zrangeWithScores(op.getKey(), start, end));
        }
        return jedis.zrange(op.getKey(), start, end);
    }

    private Object handleZRevRange(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        long start = longParam(e, "start");
        long end = longParam(e, "end");
        if (e != null && boolParam(e, "withScores")) {
            return tupleSetToMaps(jedis.zrevrangeWithScores(op.getKey(), start, end));
        }
        return jedis.zrevrange(op.getKey(), start, end);
    }

    private Object handleZRangeByScore(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        double min = doubleParam(e, "min");
        double max = doubleParam(e, "max");
        boolean withScores = e != null && boolParam(e, "withScores");
        if (e != null && e.containsKey("offset") && e.containsKey("count")) {
            int offset = intParam(e, "offset");
            int count = intParam(e, "count");
            if (withScores) return tupleSetToMaps(jedis.zrangeByScoreWithScores(op.getKey(), min, max, offset, count));
            return jedis.zrangeByScore(op.getKey(), min, max, offset, count);
        }
        if (withScores) return tupleSetToMaps(jedis.zrangeByScoreWithScores(op.getKey(), min, max));
        return jedis.zrangeByScore(op.getKey(), min, max);
    }

    private Object handleZRevRangeByScore(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        double min = doubleParam(e, "min");
        double max = doubleParam(e, "max");
        boolean withScores = e != null && boolParam(e, "withScores");
        if (e != null && e.containsKey("offset") && e.containsKey("count")) {
            int offset = intParam(e, "offset");
            int count = intParam(e, "count");
            if (withScores) return tupleSetToMaps(jedis.zrevrangeByScoreWithScores(op.getKey(), max, min, offset, count));
            return jedis.zrevrangeByScore(op.getKey(), max, min, offset, count);
        }
        if (withScores) return tupleSetToMaps(jedis.zrevrangeByScoreWithScores(op.getKey(), max, min));
        return jedis.zrevrangeByScore(op.getKey(), max, min);
    }

    private Object handleZUnionStore(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        return jedis.zunionstore(strParam(e, "destKey"), buildZParams(e),
                prependKey(op.getKey(), strArrayParam(e, "otherKeys")));
    }

    private Object handleZInterStore(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        return jedis.zinterstore(strParam(e, "destKey"), buildZParams(e),
                prependKey(op.getKey(), strArrayParam(e, "otherKeys")));
    }

    private Object handleZRangeByLex(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        if (e != null && e.containsKey("offset") && e.containsKey("count")) {
            return jedis.zrangeByLex(op.getKey(), strParam(e, "min"), strParam(e, "max"),
                    intParam(e, "offset"), intParam(e, "count"));
        }
        return jedis.zrangeByLex(op.getKey(), strParam(e, "min"), strParam(e, "max"));
    }

    private Object handleZRevRangeByLex(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        if (e != null && e.containsKey("offset") && e.containsKey("count")) {
            return jedis.zrevrangeByLex(op.getKey(), strParam(e, "max"), strParam(e, "min"),
                    intParam(e, "offset"), intParam(e, "count"));
        }
        return jedis.zrevrangeByLex(op.getKey(), strParam(e, "max"), strParam(e, "min"));
    }

    private ZParams buildZParams(Map<String, Object> extra) {
        ZParams params = new ZParams();
        if (extra != null && extra.containsKey("aggregate")) {
            String agg = String.valueOf(extra.get("aggregate"));
            params.aggregate(ZParams.Aggregate.valueOf(agg.toUpperCase()));
        }
        if (extra != null && extra.get("weights") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Number> weights = (List<Number>) extra.get("weights");
            params.weights(weights.stream().mapToDouble(Number::doubleValue).toArray());
        }
        return params;
    }

    // ==================== Key Handler ====================

    private Object handleDel(Jedis jedis, RedisOperation op) {
        Map<String, Object> e = op.getExtraParams();
        if (e != null && e.get("keys") instanceof java.util.Collection) {
            @SuppressWarnings("unchecked")
            java.util.Collection<String> keys = (java.util.Collection<String>) e.get("keys");
            return jedis.del(keys.toArray(new String[0]));
        }
        return jedis.del(op.getKey());
    }
    // ==================== Helper Methods ====================

    private void applyPostExpire(Jedis jedis, RedisOperation op) {
        if (op == null) return;
        Long expireSeconds = op.getExpireSeconds();
        if (expireSeconds != null && expireSeconds > 0) {
            String key = op.getKey();
            if (key != null) jedis.expire(key, expireSeconds.intValue());
        }
    }

    private String field(RedisOperation op) {
        if (op == null) return null;
        String f = op.getField();
        if (f != null) return f;
        Map<String, Object> extra = op.getExtraParams();
        return extra != null ? (String) extra.get("field") : null;
    }

    private String[] serializeValues(RedisOperation op) {
        Map<String, Object> extra = op.getExtraParams();
        if (extra != null && extra.get("values") instanceof List) {
            @SuppressWarnings("unchecked")
            List<?> values = (List<?>) extra.get("values");
            return values.stream().map(this::serializeValue).toArray(String[]::new);
        }
        Object value = op.getValue();
        if (value instanceof Object[]) {
            return Arrays.stream((Object[]) value).map(this::serializeValue).toArray(String[]::new);
        }
        if (value instanceof String[]) return (String[]) value;
        return new String[]{serializeValue(value)};
    }

    private String serializeValue(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        return String.valueOf(value);
    }

    // ==================== Parameter Helpers ====================

    private String strParam(Map<String, Object> extra, String key) {
        if (extra == null) return null;
        Object v = extra.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private String[] strArrayParam(Map<String, Object> extra, String key) {
        if (extra == null) return new String[0];
        Object v = extra.get(key);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) v;
            return list.stream().map(String::valueOf).toArray(String[]::new);
        }
        if (v instanceof Object[]) {
            return Arrays.stream((Object[]) v).map(String::valueOf).toArray(String[]::new);
        }
        return new String[0];
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> strMapParam(Map<String, Object> extra, String key) {
        if (extra == null) return Collections.emptyMap();
        Object v = extra.get(key);
        if (v instanceof Map) {
            Map<String, String> result = new LinkedHashMap<>();
            ((Map<Object, Object>) v).forEach((k, val) -> result.put(String.valueOf(k), String.valueOf(val)));
            return result;
        }
        return Collections.emptyMap();
    }

    private long longParam(Map<String, Object> extra, String key) {
        return safeLong(extra != null ? extra.get(key) : null);
    }

    private int intParam(Map<String, Object> extra, String key) {
        return safeInt(extra != null ? extra.get(key) : null);
    }

    private double doubleParam(Map<String, Object> extra, String key) {
        return safeDouble(extra != null ? extra.get(key) : null);
    }

    private boolean boolParam(Map<String, Object> extra, String key) {
        if (extra == null) return false;
        Object v = extra.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private long safeLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v != null) { try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) {} }
        return 0L;
    }

    private int safeInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) { try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) {} }
        return 0;
    }

    private double safeDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v != null) { try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) {} }
        return 0.0;
    }

    // ==================== Tuple Conversion ====================

    private Map<String, Object> tupleToMap(Tuple t) {
        if (t == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("value", t.getElement());
        map.put("score", t.getScore());
        return map;
    }

    private List<Map<String, Object>> tupleSetToMaps(Object result) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (result instanceof java.util.Set) {
            for (Object item : (java.util.Set<?>) result) {
                if (item instanceof Tuple) maps.add(tupleToMap((Tuple) item));
            }
        }
        return maps;
    }
}
