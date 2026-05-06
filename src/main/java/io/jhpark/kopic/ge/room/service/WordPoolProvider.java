package io.jhpark.kopic.ge.room.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class WordPoolProvider {

	private static final String WORD_POOL_RESOURCE_PATH = "word-pool.txt";

	private final List<String> words;

	public WordPoolProvider() {
		this.words = loadWords();
	}

	public List<String> words() {
		return words;
	}

	public int totalWordCount() {
		return words.size();
	}

	public List<String> pickRandomWords(int count, Set<String> excludedWords) {
		if (count <= 0) {
			return List.of();
		}
		Set<String> blockedWords = excludedWords == null ? Set.of() : excludedWords;
		List<Integer> candidateIndexes = new ArrayList<>();
		for (int index = 0; index < words.size(); index++) {
			String word = words.get(index);
			if (blockedWords.contains(word)) {
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

		List<String> pickedWords = new ArrayList<>(pickCount);
		for (int index = 0; index < pickCount; index++) {
			pickedWords.add(words.get(candidateIndexes.get(index)));
		}
		return List.copyOf(pickedWords);
	}

	private List<String> loadWords() {
		InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(WORD_POOL_RESOURCE_PATH);
		if (resourceStream == null) {
			throw new IllegalStateException("word pool resource is missing: " + WORD_POOL_RESOURCE_PATH);
		}

		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(resourceStream, StandardCharsets.UTF_8)
		)) {
			Set<String> deduplicatedWords = new LinkedHashSet<>();
			reader.lines()
				.map(String::trim)
				.filter(line -> !line.isEmpty())
				.filter(line -> !line.startsWith("#"))
				.forEach(deduplicatedWords::add);

			if (deduplicatedWords.isEmpty()) {
				throw new IllegalStateException("word pool resource is empty: " + WORD_POOL_RESOURCE_PATH);
			}
			return List.copyOf(deduplicatedWords);
		} catch (IOException exception) {
			throw new IllegalStateException("failed to load word pool resource: " + WORD_POOL_RESOURCE_PATH, exception);
		}
	}
}
