package com.dropfolio.scheduler.lock;

import com.dropfolio.common.cache.CacheKeys;
import com.dropfolio.scheduler.config.SchedulerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchedulerLockService} — M7 Implementation Authorization §13/§22.
 * {@link StringRedisTemplate} is mocked (no real Redis in this environment); real TTL
 * expiration and true concurrent-acquisition races are not exercised here (they require a real
 * Redis instance) — covered instead by the runtime verification checklist in the completion
 * report. What IS verified: the exact key built, the exact TTL passed, owner-token uniqueness,
 * and that release only ever goes through the atomic check-and-delete script.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerLockServiceTest {

    private static final String JOB_NAME = "price-sync";
    private static final String EXPECTED_KEY = "lock:scheduler:price-sync";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private SchedulerLockService lockService;

    @BeforeEach
    void setUp() {
        SchedulerProperties properties = new SchedulerProperties(
                new SchedulerProperties.PriceSync(900_000L),
                new SchedulerProperties.Lock(600L));
        lockService = new SchedulerLockService(redisTemplate, properties);
    }

    @Test
    void tryAcquire_whenFree_returnsOwnerTokenAndSetsCorrectKeyAndTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(EXPECTED_KEY), any(), eq(Duration.ofSeconds(600)))).thenReturn(true);

        String token = lockService.tryAcquire(JOB_NAME);

        assertThat(token).isNotNull();
        assertThat(CacheKeys.schedulerLock(JOB_NAME)).isEqualTo(EXPECTED_KEY);
        verify(valueOperations).setIfAbsent(eq(EXPECTED_KEY), eq(token), eq(Duration.ofSeconds(600)));
    }

    @Test
    void tryAcquire_whenAlreadyHeld_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(EXPECTED_KEY), any(), any())).thenReturn(false);

        String token = lockService.tryAcquire(JOB_NAME);

        assertThat(token).isNull();
    }

    @Test
    void tryAcquire_generatesADifferentTokenEachCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(EXPECTED_KEY), any(), any())).thenReturn(true);

        String first = lockService.tryAcquire(JOB_NAME);
        String second = lockService.tryAcquire(JOB_NAME);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void release_usesAtomicCheckAndDeleteScript_withKeyAndOwnerToken() {
        String ownerToken = "owner-123";

        lockService.release(JOB_NAME, ownerToken);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(), eq(ownerToken));

        assertThat(keysCaptor.getValue()).containsExactly(EXPECTED_KEY);
        String scriptBody = scriptCaptor.getValue().getScriptAsString();
        assertThat(scriptBody).contains("redis.call");
        assertThat(scriptBody).contains("get");
        assertThat(scriptBody).contains("del");
    }

    @Test
    void release_withNullOwnerToken_isNoOp() {
        lockService.release(JOB_NAME, null);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void release_swallowsRedisFailure_ratherThanPropagating() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenThrow(new RuntimeException("redis down"));

        // Must not throw — TTL is the safety net if release itself fails (SchedulerLockService javadoc).
        lockService.release(JOB_NAME, "owner-123");
    }
}
