package sangong.entrance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisShardInfo;
import redis.clients.jedis.ShardedJedisPool;
import sangong.redis.RedisService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 斗地主
 * Created by pengyi on 2016/3/9.
 */
public class SanGongTcpService implements Runnable {

    public static Map<Integer, MessageReceive> userClients = new HashMap<>();
    private ServerSocket serverSocket;
    private boolean started = false;
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private ExecutorService cachedThreadPool = Executors.newCachedThreadPool();

    @Override
    public void run() {

        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setMaxTotal(50000);
        jedisPoolConfig.setMaxIdle(20000);
        jedisPoolConfig.setMaxWaitMillis(3000);
        jedisPoolConfig.setTestOnBorrow(true);
        jedisPoolConfig.setTestOnReturn(true);

        List<JedisShardInfo> shards = new ArrayList<>();
        shards.add(new JedisShardInfo("127.0.0.1", 6379, 300));
        ShardedJedisPool shardedJedisPool = new ShardedJedisPool(jedisPoolConfig, shards);
        RedisService redisService = new RedisService(shardedJedisPool);

        int port = 10004;
        try {
            serverSocket = new ServerSocket(port);
            started = true;
            logger.info("跑得快tcp开启成功，端口[" + port + "]");
        } catch (IOException e) {
            logger.error("socket.open.fail.message");
            e.printStackTrace();
        }

        try {
            while (started) {
                Socket s = serverSocket.accept();
                cachedThreadPool.execute(new MessageReceive(s, redisService));
            }
        } catch (IOException e) {
            logger.error("socket.server.dirty.shutdown.message");
            e.printStackTrace();
        } finally {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
