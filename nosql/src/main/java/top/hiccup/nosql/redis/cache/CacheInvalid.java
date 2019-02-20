package top.hiccup.nosql.redis.cache;

import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【缓存穿透】
 * 术语定义：
 * 缓存穿透是指查询一个一定不存在的数据，由于缓存是命中时被动写的，并且出于容错考虑，如果从存储层查不到数据则不写入缓存，
 * 这将导致这个不存在的数据每次请求都要到存储层去查询，失去了缓存的意义。在流量大时，可能DB就挂掉了，
 * 要是有人利用不存在的key频繁攻击我们的应用，这就是漏洞。
 * 解决方案：
 * 有很多种方法可以有效地解决缓存穿透问题，最常见的则是采用布隆过滤器，将所有可能存在的数据哈希到一个足够大的bitmap中，
 * 一个一定不存在的数据会被这个bitmap拦截掉，从而避免了对底层存储系统的查询压力。
 * 另外也有一个更为简单粗暴的方法（我们采用的就是这种），如果一个查询返回的数据为空（不管是数据不存在，还是系统故障），
 * 我们仍然把这个空结果进行缓存，但它的过期时间会很短，最长不超过五分钟。
 *
 * 【缓存雪崩】
 * 术语定义：
 * 缓存雪崩是指在我们设置缓存数据时采用了相同的过期时间，导致缓存在某一时刻同时失效，请求全部转发到DB，DB瞬时压力过重而挂掉。
 * 解决方案：
 * 缓存失效时的雪崩效应对底层系统的冲击非常可怕。大多数系统设计者考虑用加锁或者队列的方式保证缓存的单线程（进程）写，
 * 从而避免失效时大量的并发请求落到底层存储系统上。这里分享一个简单方案就是将缓存失效时间分散开，
 * 比如我们可以在原有的失效时间基础上增加一个随机值，比如1-5分钟随机，这样每一个缓存的过期时间的重复率就会降低，就很难引发集体失效的事件。
 *
 * 【缓存击穿】
 * 术语定义：
 * 对于一些设置了过期时间的key，如果这些key可能会在某些时间点被超高并发地访问，是一种非常“热点”的数据。
 * 这个时候，需要考虑一个问题：缓存被“击穿”的问题，这个和缓存雪崩的区别在于这里针对某一key缓存，前者则是很多key。
 * 缓存在某个时间点过期的时候，恰好在这个时间点对这个Key有大量的并发请求过来，这些请求发现缓存过期一般都会从后端DB加载数据并回设到缓存，
 * 这个时候大并发的请求可能会瞬间把后端DB压垮。
 * 解决方案：
 * 1.使用互斥锁(mutex key)
 * 业界比较常用的做法，是使用mutex。简单地来说，就是在缓存失效的时候（判断拿出来的值为空），不是立即去load db，
 * 而是先使用缓存工具的某些带成功操作返回值的操作（比如Redis的SETNX或者Memcache的ADD）去set一个mutex key，
 * 当操作返回成功时，再进行load db的操作并回设缓存；否则，就重试整个get缓存的方法。
 * SETNX，是「SET if Not eXists」的缩写，也就是只有不存在的时候才设置，可以利用它来实现锁的效果
 * 2."提前"使用互斥锁(mutex key)：
 * 在value内部设置1个超时值(timeout1), timeout1比实际的cache timeout(timeout2)小。
 * 当从cache读取到timeout1发现它已经过期时候，马上延长timeout1并重新设置到cache。然后再从数据库加载数据并设置到cache中。
 * 3."永远不过期"
 * (1) 从redis上看，确实没有设置过期时间，这就保证了，不会出现热点key过期问题，也就是“物理”不过期。
 * (2) 从功能上看，如果不过期，那不就成静态的了吗？所以我们把过期时间存在key对应的value里，如果发现要过期了，
 * 通过一个后台的异步线程进行缓存的构建，也就是“逻辑”过期，从实战看，这种方法对于性能非常友好，
 * 唯一不足的就是构建缓存时候，其余线程(非构建缓存的线程)可能访问的是老数据，但是对于一般的互联网功能来说这个还是可以忍受。
 *
 * @author wenhy
 * @date 2018/8/18
 */
public class CacheInvalid {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;

    private static final int LOOP_TIMES = 100;

    private Jedis jedis = new Jedis(HOST, PORT);

    private AtomicInteger cacheCount = new AtomicInteger();
    private AtomicInteger dbCount = new AtomicInteger();
    private AtomicInteger nullCount = new AtomicInteger();

    public String get(String key) throws InterruptedException {
        // TODO 这里同一个jedis实例不能多线程访问，后面再查查原因
        Jedis jedis = new Jedis(HOST, PORT);
        String value = jedis.get(key);
        cacheCount.getAndIncrement();
        // 为null则表明缓存过期
        if (null == value) {
            nullCount.getAndIncrement();
            // 设置3min的超时时间，防止del操作失败的时候，下次缓存过期一直不能load db
            String mutexKey = "$mutex_"+key;
            if (jedis.setnx(mutexKey, "1") == 1) {
                // 设置锁失效时间，防止del失败的情况下锁一直被占用
                jedis.expire(mutexKey, 2 * 60);
                // 从DB获取值
                value = "haha";
                dbCount.getAndIncrement();
                jedis.set(key, value);
                jedis.expire(key, 5 * 60);
                jedis.del(mutexKey);
                return value;
            } else {
                Thread.sleep(50);
                // 递归调用进行重试
                return get(key);
            }
        } else {
            return value;
        }
    }

    /**
     * 永不过期
     * @param args
     */
//    String get(final String key) {
//        V v = redis.get(key);
//        String value = v.getValue();
//        long timeout = v.getTimeout();
//        if (v.timeout <= System.currentTimeMillis()) {
//            // 异步更新后台异常执行
//            threadPool.execute(new Runnable() {
//                public void run() {
//                    String keyMutex = "mutex:" + key;
//                    if (redis.setnx(keyMutex, "1")) {
//                        // 3 min timeout to avoid mutex holder crash
//                        redis.expire(keyMutex, 3 * 60);
//                        String dbValue = db.get(key);
//                        redis.set(key, dbValue);
//                        redis.delete(keyMutex);
//                    }
//                }
//            });
//        }
//        return value;
//    }

    public static void main(String[] args) {
        final CacheInvalid main = new CacheInvalid();
        List<Thread> threads = new ArrayList<>(LOOP_TIMES);
        final CyclicBarrier barrier = new CyclicBarrier(LOOP_TIMES);
        for(int i=0; i<LOOP_TIMES; i++) {
            threads.add(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        System.out.println("阻塞前");
                        barrier.await();
                        System.out.println("阻塞后");
                        System.out.println(main.get("key222"));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (BrokenBarrierException e) {
                        e.printStackTrace();
                    }
                }
            }));
        }
        for(int i=0; i<LOOP_TIMES; i++) {
            threads.get(i).start();
        }
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println(main.cacheCount);
        System.out.println(main.dbCount);
        System.out.println(main.nullCount);
    }

}