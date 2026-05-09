package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.CustomWordMode;
import io.jhpark.kopic.ge.room.dto.WordEntry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class WordPoolProvider {

	private static final String WORD_POOL_RESOURCE_PATH = "word-pool.txt";
	private static final char WORD_DESCRIPTION_SEPARATOR = '|';

	private final List<WordEntry> baseWordPool;

	public WordPoolProvider() {
		this.baseWordPool = loadWords();
	}

	public List<WordEntry> parseCustomWordPool(String customWordsRaw) {
		if (customWordsRaw == null || customWordsRaw.isBlank()) {
			return List.of();
		}
		Map<String, WordEntry> deduplicatedWords = new LinkedHashMap<>();
		String[] rawTokens = customWordsRaw.split(",");
		for (String rawToken : rawTokens) {
			WordEntry parsedEntry = parseWordEntry(rawToken);
			if (parsedEntry == null) {
				continue;
			}
			deduplicatedWords.putIfAbsent(parsedEntry.word(), parsedEntry);
		}
		return List.copyOf(deduplicatedWords.values());
	}

	public List<WordEntry> pickRandomWords(
		int count,
		Set<String> excludedWords,
		CustomWordMode customWordMode,
		List<WordEntry> gameCustomPool
	) {
		if (count <= 0) {
			return List.of();
		}
		List<WordEntry> sourceWords = resolveSourceWords(customWordMode, gameCustomPool);
		if (sourceWords.isEmpty()) {
			return List.of();
		}
		Set<String> blockedWords = excludedWords == null ? Set.of() : excludedWords;
		List<Integer> candidateIndexes = new ArrayList<>();
		for (int index = 0; index < sourceWords.size(); index++) {
			WordEntry wordEntry = sourceWords.get(index);
			if (wordEntry == null || wordEntry.word() == null || wordEntry.word().isBlank()) {
				continue;
			}
			if (blockedWords.contains(wordEntry.word())) {
				continue;
			}
			candidateIndexes.add(index);
		}
		if (candidateIndexes.isEmpty()) {
			return List.of();
		}

		int pickCount = Math.min(count, candidateIndexes.size());
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int index = 0; index < pickCount; index++) {
			int swapIndex = random.nextInt(index, candidateIndexes.size());
			if (swapIndex == index) {
				continue;
			}
			Integer temp = candidateIndexes.get(index);
			candidateIndexes.set(index, candidateIndexes.get(swapIndex));
			candidateIndexes.set(swapIndex, temp);
		}

		List<WordEntry> pickedWords = new ArrayList<>(pickCount);
		for (int index = 0; index < pickCount; index++) {
			pickedWords.add(sourceWords.get(candidateIndexes.get(index)));
		}
		return List.copyOf(pickedWords);
	}

	public List<WordEntry> pickRandomWordsAllowDuplicate(
		int count,
		CustomWordMode customWordMode,
		List<WordEntry> gameCustomPool
	) {
		if (count <= 0) {
			return List.of();
		}
		List<WordEntry> sourceWords = resolveSourceWords(customWordMode, gameCustomPool);
		if (sourceWords.isEmpty()) {
			return List.of();
		}
		List<Integer> candidateIndexes = new ArrayList<>();
		for (int index = 0; index < sourceWords.size(); index++) {
			WordEntry wordEntry = sourceWords.get(index);
			if (wordEntry == null || wordEntry.word() == null || wordEntry.word().isBlank()) {
				continue;
			}
			candidateIndexes.add(index);
		}
		if (candidateIndexes.isEmpty()) {
			return List.of();
		}

		ThreadLocalRandom random = ThreadLocalRandom.current();
		List<WordEntry> pickedWords = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			int candidateIndex = candidateIndexes.get(random.nextInt(candidateIndexes.size()));
			pickedWords.add(sourceWords.get(candidateIndex));
		}
		return List.copyOf(pickedWords);
	}

	private List<WordEntry> resolveSourceWords(CustomWordMode customWordMode, List<WordEntry> gameCustomPool) {
		List<WordEntry> customPool = gameCustomPool == null ? List.of() : gameCustomPool;
		if (customWordMode == CustomWordMode.CUSTOM_ONLY) {
			return customPool;
		}
		if (customWordMode == CustomWordMode.BASE_PLUS_CUSTOM) {
			return mergeBaseAndCustom(customPool);
		}
		throw new IllegalArgumentException("customWordMode must not be null");
	}

	private List<WordEntry> mergeBaseAndCustom(List<WordEntry> customPool) {
		if (customPool.isEmpty()) {
			return baseWordPool;
		}
		Map<String, WordEntry> merged = new LinkedHashMap<>();
		for (WordEntry wordEntry : baseWordPool) {
			if (wordEntry == null || wordEntry.word() == null || wordEntry.word().isBlank()) {
				continue;
			}
			merged.putIfAbsent(wordEntry.word(), wordEntry);
		}
		for (WordEntry wordEntry : customPool) {
			if (wordEntry == null || wordEntry.word() == null || wordEntry.word().isBlank()) {
				continue;
			}
			merged.put(wordEntry.word(), wordEntry);
		}
		return List.copyOf(merged.values());
	}

	private List<WordEntry> loadWords() {
		InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(WORD_POOL_RESOURCE_PATH);
		if (resourceStream == null) {
			throw new IllegalStateException("word pool resource is missing: " + WORD_POOL_RESOURCE_PATH);
		}

		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(resourceStream, StandardCharsets.UTF_8)
		)) {
			Map<String, WordEntry> deduplicatedWords = new LinkedHashMap<>();
			reader.lines()
				.map(String::trim)
				.filter(line -> !line.isEmpty())
				.filter(line -> !line.startsWith("#"))
				.forEach(line -> {
					WordEntry parsedEntry = parseWordEntry(line);
					if (parsedEntry == null) {
						return;
					}
					deduplicatedWords.putIfAbsent(parsedEntry.word(), parsedEntry);
				});

			if (deduplicatedWords.isEmpty()) {
				throw new IllegalStateException("word pool resource is empty: " + WORD_POOL_RESOURCE_PATH);
			}
			return List.copyOf(deduplicatedWords.values());
		} catch (IOException exception) {
			throw new IllegalStateException("failed to load word pool resource: " + WORD_POOL_RESOURCE_PATH, exception);
		}
	}

	private WordEntry parseWordEntry(String rawValue) {
		if (rawValue == null) {
			return null;
		}
		String trimmed = rawValue.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		int separatorIndex = trimmed.indexOf(WORD_DESCRIPTION_SEPARATOR);
		if (separatorIndex < 0) {
			return new WordEntry(trimmed, null);
		}
		String word = trimmed.substring(0, separatorIndex).trim();
		if (word.isEmpty()) {
			return null;
		}
		String rawDescription = trimmed.substring(separatorIndex + 1).trim();
		String description = rawDescription.isEmpty() ? null : rawDescription;
		return new WordEntry(word, description);
	}
}
