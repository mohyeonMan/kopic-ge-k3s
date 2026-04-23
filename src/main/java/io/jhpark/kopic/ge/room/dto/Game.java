package io.jhpark.kopic.ge.room.dto;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import lombok.Getter;

@Getter
public class Game {

    private static final String GAME_ID_PREFIX = "gid_";
    private static final String ROUND_ID_PREFIX = "grd_";
    private static final String TURN_ID_PREFIX = "trn_";
    private static final int ID_SUFFIX_LENGTH = 8;

    // 게임 전체 상태
    private String gameId;
    private Setting gameSetting;
    private GamePhase gamePhase;
    private LinkedHashMap<String, Integer> totalPoints;
    private Instant startedAt;
    
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
    private String answerWord;
    private String curDrawerSid;
    private Map<String, Integer> earnedPoints;

    private Game(Setting gameSetting){
        this.gameId = newId(GAME_ID_PREFIX);
        this.gameSetting = gameSetting.copy();
        this.gamePhase = GamePhase.PLAYING;
        this.totalPoints = new LinkedHashMap<>();
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

        String nextDrawerSid = this.curRoundDrawerSids.poll();
        if (nextDrawerSid == null) {
            throw new IllegalArgumentException("turnIndex out of range");
        }

        this.curTurnIndex += 1;
        this.curTurnId = newId(TURN_ID_PREFIX);
        this.turnPhase = TurnPhase.STARTING;
        this.curDrawerSid = nextDrawerSid;
        this.wordCandidates = List.of();
        this.answerWord = null;
        this.earnedPoints = new HashMap<>();
    }

    public void openWordCandidate(List<String> words) {
        Objects.requireNonNull(words, "words");

        this.wordCandidates = List.copyOf(words);
        this.answerWord = null;
        this.turnPhase = TurnPhase.WORD_CHOICE;
    }

    public void startDrawing(int choiceIndex) {
        this.answerWord = this.wordCandidates.get(choiceIndex);
        this.turnPhase = TurnPhase.DRAWING;
    }

    public void finishTurnResult() {
        this.turnPhase = TurnPhase.TURN_RESULT;
    }

    public void finishRoundResult() {
        this.roundPhase = RoundPhase.FINISHED;
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
