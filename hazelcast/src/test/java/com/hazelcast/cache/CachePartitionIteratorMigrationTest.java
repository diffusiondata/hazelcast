package com.hazelcast.cache;

import com.hazelcast.cache.impl.CacheProxy;
import com.hazelcast.cache.impl.HazelcastServerCachingProvider;
import com.hazelcast.config.CacheConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spi.properties.GroupProperty;
import com.hazelcast.test.HazelcastParametersRunnerFactory;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.spi.CachingProvider;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(HazelcastParametersRunnerFactory.class)
@Category({QuickTest.class, ParallelTest.class})
public class CachePartitionIteratorMigrationTest extends HazelcastTestSupport {

    @Parameterized.Parameter
    public boolean prefetchValues;

    @Parameterized.Parameters(name = "prefetchValues:{0}")
    public static Iterable<Object[]> parameters() {
        return Arrays.asList(new Object[]{Boolean.TRUE}, new Object[]{Boolean.FALSE});
    }

    protected CachingProvider createCachingProvider(HazelcastInstance server) {
        return HazelcastServerCachingProvider.createCachingProvider(server);
    }

    private <K, V> CacheProxy<K, V> getCacheProxy(CachingProvider cachingProvider) {
        String cacheName = randomString();
        CacheManager cacheManager = cachingProvider.getCacheManager();
        CacheConfig<K, V> config = new CacheConfig<K, V>();
        config.getEvictionConfig().setMaximumSizePolicy(EvictionConfig.MaxSizePolicy.ENTRY_COUNT).setSize(10000000);
        return (CacheProxy<K, V>) cacheManager.createCache(cacheName, config);

    }

    @Test
    public void testIterator_DoesNotReturn_DuplicateEntry_When_Rehashing_Happens() throws Exception {
        HazelcastInstance instance = createHazelcastInstance();
        CacheProxy<String, String> proxy = getCacheProxy(createCachingProvider(instance));
        HashSet<String> readKeys = new HashSet<String>();

        String value = "initialValue";
        putValuesToPartition(instance, proxy, value, 1, 100);
        Iterator<Cache.Entry<String, String>> iterator = proxy.iterator(10, 1, prefetchValues);
        assertUniques(readKeys, iterator, 50);
        // force rehashing
        putValuesToPartition(instance, proxy, randomString(), 1, 150);
        assertUniques(readKeys, iterator);

    }

    @Test
    public void testIterator_DoesNotReturn_DuplicateEntry_When_Migration_Happens() throws Exception {
        Config config = new Config();
        config.setProperty(GroupProperty.PARTITION_COUNT.getName(), "2");
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory();
        HazelcastInstance instance = factory.newHazelcastInstance(config);
        CacheProxy<String, String> proxy = getCacheProxy(createCachingProvider(instance));

        HashSet<String> readKeysP1 = new HashSet<String>();
        HashSet<String> readKeysP2 = new HashSet<String>();

        String value = "value";
        putValuesToPartition(instance, proxy, value, 0, 100);
        putValuesToPartition(instance, proxy, value, 1, 100);

        Iterator<Cache.Entry<String, String>> iteratorP1 = proxy.iterator(10, 0, prefetchValues);
        Iterator<Cache.Entry<String, String>> iteratorP2 = proxy.iterator(10, 1, prefetchValues);
        assertUniques(readKeysP1, iteratorP1, 50);
        assertUniques(readKeysP2, iteratorP2, 50);
        // force migration
        factory.newHazelcastInstance(config);
        // force rehashing
        putValuesToPartition(instance, proxy, randomString(), 0, 150);
        putValuesToPartition(instance, proxy, randomString(), 1, 150);
        assertUniques(readKeysP1, iteratorP1);
        assertUniques(readKeysP2, iteratorP2);
    }

    private void assertUniques(HashSet<String> readKeys, Iterator<Cache.Entry<String, String>> iterator) {
        while (iterator.hasNext()) {
            Cache.Entry<String, String> entry = iterator.next();
            boolean unique = readKeys.add(entry.getKey());
            Assert.assertTrue(unique);
        }
    }

    private void assertUniques(HashSet<String> readKeys, Iterator<Cache.Entry<String, String>> iterator, int numberOfItemsToRead) {
        int count = 0;
        while (iterator.hasNext() && count++ < numberOfItemsToRead) {
            Cache.Entry<String, String> entry = iterator.next();
            boolean unique = readKeys.add(entry.getKey());
            Assert.assertTrue(unique);
        }
    }

    private void putValuesToPartition(HazelcastInstance instance, CacheProxy<String, String> proxy, String value, int partitionId, int count) {
        for (int i = 0; i < count; i++) {
            String key = generateKeyForPartition(instance, partitionId);
            proxy.put(key, value);
        }
    }

}
