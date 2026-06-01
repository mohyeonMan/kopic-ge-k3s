package io.jhpark.kopic.ge.common.redis;

import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisService {

	private final StringRedisTemplate redisTemplate;

	public void set(String key, String value, Duration timeout) {
		redisTemplate.opsForValue().set(key, value, timeout);
	}

	public Boolean setIfAbsent(String key, String value, Duration timeout) {
		return redisTemplate.opsForValue().setIfAbsent(key, value, timeout);
	}

	public String get(String key) {
		return redisTemplate.opsForValue().get(key);
	}

	public Boolean delete(String key) {
		return redisTemplate.delete(key);
	}

	public Long sAdd(String key, String member) {
		return redisTemplate.opsForSet().add(key, member);
	}

	public Long sRemove(String key, String member) {
		return redisTemplate.opsForSet().remove(key, member);
	}

	public Set<String> sMembers(String key) {
		return redisTemplate.opsForSet().members(key);
	}

	public Boolean zAdd(String key, String member, double score) {
		return redisTemplate.opsForZSet().add(key, member, score);
	}

	public Long zRemove(String key, String member) {
		return redisTemplate.opsForZSet().remove(key, member);
	}

	public Set<String> zRange(String key, long start, long end) {
		return redisTemplate.opsForZSet().range(key, start, end);
	}
}
