package xjcs;

/**
 * Created by zhangxinyi on 2017/8/26.
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisMovedDataException;
import redis.clients.jedis.exceptions.JedisRedirectionException;
import redis.clients.util.JedisClusterCRC16;
import redis.clients.util.SafeEncoder;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.util.*;

/**
 *
 *
 *
 * @author youaremoon
 * @version
 * @since Ver 1.1
 */
public class JedisClusterPipeline extends PipelineBase implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(JedisClusterPipeline.class);

    private static final Field FIELD_CONNECTION_HANDLER;
    private static final Field FIELD_CACHE;
    static {
        FIELD_CONNECTION_HANDLER = getField(BinaryJedisCluster.class, "connectionHandler");
        FIELD_CACHE = getField(JedisClusterConnectionHandler.class, "cache");
    }

    private JedisSlotBasedConnectionHandler connectionHandler;
    private JedisClusterInfoCache clusterInfoCache;
    private Queue<Client> clients = new LinkedList<Client>();
    private Map<JedisPool, Jedis> jedisMap = new HashMap<>();
    private boolean hasDataInBuf = false;

    /**
     *
     * @param
     * @return
     */
    public static JedisClusterPipeline pipelined(JedisCluster jedisCluster) {
        JedisClusterPipeline pipeline = new JedisClusterPipeline();
        pipeline.setJedisCluster(jedisCluster);
        return pipeline;
    }

    public JedisClusterPipeline() {
    }

    public void setJedisCluster(JedisCluster jedis) {
        connectionHandler = getValue(jedis, FIELD_CONNECTION_HANDLER);
        clusterInfoCache = getValue(connectionHandler, FIELD_CACHE);
    }

    /**
     *
     * @param
     * @return
     */
    public void refreshCluster() {
        connectionHandler.renewSlotCache();
    }

    /**
     *
     */
    public void sync() {
        innerSync(null);
    }

    /**
     *
     *
     * @return
     */
    public List<Object> syncAndReturnAll() {
        List<Object> responseList = new ArrayList<Object>();

        innerSync(responseList);

        return responseList;
    }

    private void innerSync(List<Object> formatted) {
        HashSet<Client> clientSet = new HashSet<Client>();

        try {
            for (Client client : clients) {
                Object data = generateResponse(client.getOne()).get();
                if (null != formatted) {
                    formatted.add(data);
                }

                if (clientSet.size() != jedisMap.size()) {
                    clientSet.add(client);
                }
            }
        } catch (JedisRedirectionException jre) {
            if (jre instanceof JedisMovedDataException) {
                // if MOVED redirection occurred, rebuilds cluster's slot cache,
                // recommended by Redis cluster specification
                refreshCluster();
            }

            throw jre;
        } finally {
            if (clientSet.size() != jedisMap.size()) {
                for (Jedis jedis : jedisMap.values()) {
                    if (clientSet.contains(jedis.getClient())) {
                        continue;
                    }

                    flushCachedData(jedis);
                }
            }

            hasDataInBuf = false;
            close();
        }
    }

    @Override
    public void close() {
        clean();

        clients.clear();

        for (Jedis jedis : jedisMap.values()) {
            if (hasDataInBuf) {
                flushCachedData(jedis);
            }

            jedis.close();
        }

        jedisMap.clear();

        hasDataInBuf = false;
    }

    private void flushCachedData(Jedis jedis) {
        try {
            jedis.getClient().getAll();
        } catch (RuntimeException ex) {
        }
    }

    @Override
    protected Client getClient(String key) {
        byte[] bKey = SafeEncoder.encode(key);

        return getClient(bKey);
    }

    @Override
    protected Client getClient(byte[] key) {
        Jedis jedis = getJedis(JedisClusterCRC16.getSlot(key));

        Client client = jedis.getClient();
        clients.add(client);

        return client;
    }

    private Jedis getJedis(int slot) {
        JedisPool pool = clusterInfoCache.getSlotPool(slot);

        Jedis jedis = jedisMap.get(pool);
        if (null == jedis) {
            jedis = pool.getResource();
            jedisMap.put(pool, jedis);
        }

        hasDataInBuf = true;
        return jedis;
    }

    private static Field getField(Class<?> cls, String fieldName) {
        try {
            Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);

            return field;
        } catch (NoSuchFieldException | SecurityException e) {
            throw new RuntimeException("cannot find or access field '" + fieldName + "' from " + cls.getName(), e);
        }
    }

    @SuppressWarnings({"unchecked" })
    private static <T> T getValue(Object obj, Field field) {
        try {
            return (T)field.get(obj);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            LOGGER.error("get value fail", e);

            throw new RuntimeException(e);
        }
    }
}