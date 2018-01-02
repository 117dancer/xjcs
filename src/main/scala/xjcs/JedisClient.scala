package xjcs

import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import redis.clients.jedis.{HostAndPort, JedisCluster}
import java.util

/*
object RedisClient extends Serializable {
    val redisHost = "localhost"
    val redisPort = 6380
    val redisTimeout = 60000
     val maxTotal=10000

  val poolConfig = new GenericObjectPoolConfig()
    poolConfig.setMaxTotal(maxTotal)
    poolConfig.setMinIdle(0)
    poolConfig.setMaxIdle(150)
    lazy val pool = new JedisPool(poolConfig, redisHost, redisPort, redisTimeout)

    lazy val hook = new Thread {
      override def run(): Unit = {
        println("Execute hook thread: " + this)
        pool.destroy()

      }
    }
    sys.addShutdownHook(hook.run())
  }
*/

object JedisClient extends Serializable {
  def getJedisCluster(): JedisCluster = {

    val jedisClusterNodes = new util.HashSet[HostAndPort]()
    jedisClusterNodes.add(new HostAndPort("21.36.128.76", 6380))
    //    jedisClusterNodes.add(new HostAndPort("220.177.92.237", 6382))
    //    jedisClusterNodes.add(new HostAndPort("220.171.92.237", 6383))
    //    jedisClusterNodes.add(new HostAndPort("220.171.92.237", 6384))
    //    jedisClusterNodes.add(new HostAndPort("220.171.92.237", 6385))
    //    jedisClusterNodes.add(new HostAndPort("220.171.92.237", 6386))
    val redisTimeout = 60000
    val maxTotal = 10000

    val poolConfig = new GenericObjectPoolConfig()

    poolConfig.setJmxEnabled(false)
    poolConfig.setMaxTotal(maxTotal)
    poolConfig.setMaxIdle(150)
    //  poolConfig.setMinIdle(0)
    val jc: JedisCluster = new JedisCluster(jedisClusterNodes, redisTimeout, 10, poolConfig)
    //  lazy val pool = new JedisPool(poolConfig, redisHost, redisPort, redisTimeout)

    //  lazy val hook = new Thread {
    //    override def run(): Unit = {
    //      println("Execute hook thread: " + this)
    //      jc.destroy()
    //
    //    }
    //  }
    //  sys.addShutdownHook(hook.run())
    jc
  }
}


