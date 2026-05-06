package io.jhpark.kopic.ge.room.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;

@Getter
public class Game {

    private static final String GAME_ID_PREFIX = "gid_";
    private static final String ROUND_ID_PREFIX = "grd_";
    private static final String TURN_ID_PREFIX = "trn_";
    private static final int ID_SUFFIX_LENGTH = 8;
    private static final int RECENT_ANSWER_WORD_LIMIT = 100;

    // 게임 전체 상태
    private String gameId;
    private Setting gameSetting;
    private GamePhase gamePhase;
    private LinkedHashMap<String, Integer> totalPoints;
    private Instant startedAt;
    private Instant deadlineAt;
    
    // 라운드 상태
    private int curRoundIndex;
    private String curRoundId;
    private RoundPhase roundPhase;
    private Queue<String> curRoundDrawerSids;

    // 턴 상태
    private String curTurnId;
    private int curTurnIndex;
    private TurnPhase turnPhase;
    private List<String> wordCandidates;
    private Queue<String> recentAnswerWords;
    private Map<String, Integer> recentAnswerWordCounts;
    private String answerWord;
    private String hintPattern;
    private Queue<Integer> pendingHintIndexes;
    private int hintTotalRevealCount;
    private int hintRevealedCount;
    private String curDrawerSid;
    private Map<String, Integer> earnedPoints;

    private Game(Setting gameSetting){
        this.gameId = newId(GAME_ID_PREFIX);
        this.gameSetting = gameSetting.copy();
        this.gamePhase = GamePhase.PLAYING;
        this.totalPoints = new LinkedHashMap<>();
        this.recentAnswerWords = new ArrayDeque<>();
        this.recentAnswerWordCounts = new HashMap<>();
        this.pendingHintIndexes = new ArrayDeque<>();
        this.startedAt = Instant.now();
        this.roundPhase = RoundPhase.READY;
    }
    
    public static Game start(Setting gameSetting) {
        Objects.requireNonNull(gameSetting,   "gameSetting");

        return new Game(gameSetting);

    }

    public void startRound(List<String> participantSids) {
        Objects.requireNonNull(participantSids, "participants");

        if (participantSids.isEmpty()) {
            throw new IllegalArgumentException("participants must not be empty");
        }

        int nextRoundIndex = curRoundIndex + 1;

        if(nextRoundIndex < 1 || nextRoundIndex > this.gameSetting.roundCount())
            throw new IllegalArgumentException("roundIndex out of range");

        this.curRoundIndex = nextRoundIndex;
        this.curRoundId = newId(ROUND_ID_PREFIX);
        this.roundPhase = RoundPhase.PLAYING;
        this.curRoundDrawerSids = new ArrayDeque<>(participantSids);

        this.turnPhase = TurnPhase.READY;
    }

    public void startTurn() {

        if (this.curRoundDrawerSids == null || this.curRoundDrawerSids.isEmpty()) {
            throw new IllegalArgumentException("turnIndex out of range");
        }

        String nextDrawerSid = this.curRoundDrawerSids.peek();
        if (nextDrawerSid == null) {
            throw new IllegalArgumentException("turnIndex out of range");
        }

        this.curTurnIndex += 1;
        this.curTurnId = newId(TURN_ID_PREFIX);
        this.turnPhase = TurnPhase.STARTING;
        this.curDrawerSid = nextDrawerSid;
        this.wordCandidates = List.of();
        this.answerWord = null;
        this.hintPattern = null;
        this.pendingHintIndexes.clear();
        this.hintTotalRevealCount = 0;
        this.hintRevealedCount = 0;
        this.earnedPoints = new HashMap<>();
    }

    public void consumeCurrentTurnDrawer() {
        if (this.curRoundDrawerSids == null || this.curRoundDrawerSids.isEmpty()) {
            return;
        }
        if (this.curDrawerSid == null || this.curDrawerSid.isBlank()) {
            return;
        }
        this.curRoundDrawerSids.remove(this.curDrawerSid);
    }

    public void openWordCandidate(List<String> words) {
        Objects.requireNonNull(words, "words");

        this.wordCandidates = List.copyOf(words);
        this.answerWord = null;
        this.turnPhase = TurnPhase.WORD_CHOICE;
    }

    public void startDrawing(int choiceIndex) {
        this.answerWord = this.wordCandidates.get(choiceIndex);
        trackRecentAnswerWord(this.answerWord);
        initializeHintState(this.answerWord);
        this.turnPhase = TurnPhase.DRAWING;
    }

    public int revealHintLetters(int requestedCount) {
        if (requestedCount <= 0
            || this.pendingHintIndexes == null
            || this.pendingHintIndexes.isEmpty()
            || this.hintPattern == null
            || this.answerWord == null) {
            return 0;
        }

        char[] hintChars = this.hintPattern.toCharArray();
        int revealedCount = 0;
        while (revealedCount < requestedCount && !this.pendingHintIndexes.isEmpty()) {
            Integer revealIndex = this.pendingHintIndexes.poll();
            if (revealIndex == null
                || revealIndex < 0
                || revealIndex >= hintChars.length
                || revealIndex >= this.answerWord.length()) {
                continue;
            }
            char answerChar = this.answerWord.charAt(revealIndex);
            if (hintChars[revealIndex] == answerChar) {
                continue;
            }
            hintChars[revealIndex] = answerChar;
            revealedCount += 1;
        }

        if (revealedCount > 0) {
            this.hintPattern = new String(hintChars);
            this.hintRevealedCount += revealedCount;
        }
        return revealedCount;
    }

    public boolean hasPendingHintReveals() {
        return this.pendingHintIndexes != null && !this.pendingHintIndexes.isEmpty();
    }

    private void trackRecentAnswerWord(String word) {
        if (word == null || word.isBlank()) {
            return;
        }

        this.recentAnswerWords.add(word);
        this.recentAnswerWordCounts.merge(word, 1, Integer::sum);

        if (this.recentAnswerWords.size() <= RECENT_ANSWER_WORD_LIMIT) {
            return;
        }

        String oldestWord = this.recentAnswerWords.poll();
        if (oldestWord == null) {
            return;
        }
        Integer oldCount = this.recentAnswerWordCounts.get(oldestWord);
        if (oldCount == null || oldCount <= 1) {
            this.recentAnswerWordCounts.remove(oldestWord);
            return;
        }
        this.recentAnswerWordCounts.put(oldestWord, oldCount - 1);
    }

    private void initializeHintState(String answer) {
        if (answer == null || answer.isBlank()) {
            this.hintPattern = null;
            this.pendingHintIndexes.clear();
            this.hintTotalRevealCount = 0;
            this.hintRevealedCount = 0;
            return;
        }

        List<Integer> revealableIndexes = new ArrayList<>();
        StringBuilder patternBuilder = new StringBuilder(answer.length());
        for (int index = 0; index < answer.length(); index += 1) {
            char character = answer.charAt(index);
            if (isHintRevealableChar(character)) {
                revealableIndexes.add(index);
                patternBuilder.append('_');
            } else {
                patternBuilder.append(character);
            }
        }

        int maxRevealCount = Math.max(0, revealableIndexes.size() - 1);
        if (revealableIndexes.size() > 1) {
            Collections.shuffle(revealableIndexes, ThreadLocalRandom.current());
        }

        this.hintPattern = patternBuilder.toString();
        this.pendingHintIndexes.clear();
        for (int index = 0; index < maxRevealCount; index += 1) {
            this.pendingHintIndexes.add(revealableIndexes.get(index));
        }
        this.hintTotalRevealCount = maxRevealCount;
        this.hintRevealedCount = 0;
    }

    private boolean isHintRevealableChar(char character) {
        return Character.isLetterOrDigit(character);
    }

    public void finishTurnResult() {
        this.turnPhase = TurnPhase.TURN_RESULT;
    }

    public void finishRoundResult() {
        this.roundPhase = RoundPhase.FINISHED;
    }

    public void finishGameResult() {
        this.gamePhase = GamePhase.GAME_RESULT;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    }

    public void clearDeadlineAt() {
        this.deadlineAt = null;
    }

    public void readyNextTurn(){
        this.turnPhase = TurnPhase.READY;
    }

    public void readyNextRound(){
        this.roundPhase = RoundPhase.READY;
    }

    public boolean hasNextTurn() {
        return this.curRoundDrawerSids != null && !this.curRoundDrawerSids.isEmpty();
    }

    public boolean hasNextRound() {
        if (this.gameSetting == null) {
            return false;
        }
        return this.curRoundIndex < this.gameSetting.roundCount();
    }

    public boolean isPlaying() {
        return gamePhase == GamePhase.PLAYING;
    }

    public boolean isGameResult() {
        return gamePhase == GamePhase.GAME_RESULT;
    }

    public void removeParticipant(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        if (this.totalPoints != null) {
            this.totalPoints.remove(sessionId);
        }
        if (this.earnedPoints != null) {
            this.earnedPoints.remove(sessionId);
        }
        if (sessionId.equals(this.curDrawerSid)) {
            this.curDrawerSid = null;
        }
        if (this.curRoundDrawerSids == null || this.curRoundDrawerSids.isEmpty()) {
            return;
        }

        this.curRoundDrawerSids.remove(sessionId);
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, ID_SUFFIX_LENGTH);
    }

    public enum GamePhase {
        READY,
        PLAYING,
        GAME_RESULT
    }

    public enum RoundPhase {
        READY,
        PLAYING,
        FINISHED
    }

    public enum TurnPhase {
        READY,
        STARTING, 
        WORD_CHOICE,
        DRAWING,
        TURN_RESULT
    }
}
