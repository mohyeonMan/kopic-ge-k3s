package io.jhpark.kopic.ge.room.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private List<String> curRoundDrawerSids;

    // 턴 상태
    private String curTurnId;
    private int curTurnIndex;
    private TurnPhase turnPhase;
    private List<String> wordCandidates;
    private String answerWord;
    private String curDrawerSid;
    private Map<String, Integer> earnedPoints;

    private Game(Setting gameSetting, List<Participant> participants){
        this.gameId = newId(GAME_ID_PREFIX);
        this.gameSetting = gameSetting.copy();
        this.gamePhase = GamePhase.PLAYING;
        this.totalPoints = new LinkedHashMap<>();
        this.startedAt = Instant.now();
        this.curRoundIndex = 0;
        this.curRoundId = null;
        this.roundPhase = RoundPhase.STARTING;
        this.curRoundDrawerSids = List.of();
        this.curTurnId = null;
        this.curTurnIndex = -1;
        this.turnPhase = TurnPhase.TURN_RESULT;
        this.wordCandidates = List.of();
        this.answerWord = null;
        this.curDrawerSid = null;
        this.earnedPoints = new HashMap<>();
    }
    
    public static Game start(Setting gameSetting, List<Participant> participants) {
        Objects.requireNonNull(gameSetting, "gameSetting");
        Objects.requireNonNull(participants, "participantSids");
        if (participants.size() < 2) {
            throw new IllegalArgumentException("participantSids must not be empty");
        }

        return new Game(gameSetting, participants);

    }

    public void startRound(List<Participant> participants) {
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("participants must not be empty");
        }

        int nextRoundIndex = curRoundIndex + 1;

        if(nextRoundIndex < 1 || nextRoundIndex > this.gameSetting.roundCount())
            throw new IllegalArgumentException("roundIndex out of range");

        this.curRoundIndex = nextRoundIndex;
        this.curRoundId = newId(ROUND_ID_PREFIX);
        this.roundPhase = RoundPhase.STARTING;
        this.curRoundDrawerSids = new ArrayList<>();

        for(Participant participant : participants){
            curRoundDrawerSids.add(participant.sessionId());
        }

        // 라운드가 시작되면 턴 시드는 초기 상태로 리셋한다.
        this.curTurnId = null;
        this.curTurnIndex = -1;
        this.turnPhase = TurnPhase.TURN_RESULT;
        this.wordCandidates = List.of();
        this.answerWord = null;
        this.curDrawerSid = null;
        this.earnedPoints = new HashMap<>();

    }

    public void startTurn() {

        if(this.turnPhase != TurnPhase.TURN_RESULT)
            throw new IllegalArgumentException("previous turn not finished");

        int nextTurnIndex = curTurnIndex + 1;

        if (nextTurnIndex < 0 || nextTurnIndex >= this.curRoundDrawerSids.size()) 
            throw new IllegalArgumentException("turnIndex out of range");
        

        this.curTurnIndex = nextTurnIndex;
        this.curTurnId = newId(TURN_ID_PREFIX);
        this.turnPhase = TurnPhase.STARTING;
        this.roundPhase = RoundPhase.PLAYING;
        this.curDrawerSid = this.curRoundDrawerSids.get(curTurnIndex);
        this.wordCandidates = List.of();
        this.answerWord = null;
        this.earnedPoints = new HashMap<>();
    }

    public void openWordCandidate(List<String> words) {
        Objects.requireNonNull(words, "words");

        if(this.turnPhase != TurnPhase.STARTING)
            throw new IllegalArgumentException("can't choose word this phase");

        if (words.isEmpty() || words.size() != this.gameSetting.wordChoiceCount()) 
            throw new IllegalArgumentException("illegal word counts");
        
        this.wordCandidates = List.copyOf(words);
        this.answerWord = null;
        this.turnPhase = TurnPhase.WORD_CHOICE;
    }

    public void startDrawing(String sessionId,int choiceIndex) {
        if(sessionId != curDrawerSid)
            throw new IllegalArgumentException("not a current drawer");


        if (this.turnPhase != TurnPhase.WORD_CHOICE) {
            throw new IllegalStateException("turnPhase must be WORD_CHOICE");
        }
        if (this.wordCandidates == null || this.wordCandidates.isEmpty()) {
            throw new IllegalStateException("words must not be empty");
        }
        if (choiceIndex < 0 || choiceIndex >= this.wordCandidates.size()) {
            throw new IllegalArgumentException("choiceIndex out of range");
        }

        // 단어가 선택되면 정답을 확정하고 DRAWING 단계로 진입한다.
        this.answerWord = this.wordCandidates.get(choiceIndex);
        this.turnPhase = TurnPhase.DRAWING;
    }

    public void finishTurnResult() {
        this.turnPhase = TurnPhase.TURN_RESULT;
    }

    public void finishRoundResult() {
        this.roundPhase = RoundPhase.ROUND_RESULT;
    }

    public boolean hasNextTurn() {
        if (this.curRoundDrawerSids == null || this.curRoundDrawerSids.isEmpty()) {
            return false;
        }
        // 턴 인덱스는 현재 라운드의 순서 리스트 기준 0부터 시작한다.
        return this.curTurnIndex + 1 < this.curRoundDrawerSids.size();
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

        int removedIndex = this.curRoundDrawerSids.indexOf(sessionId);
        if (removedIndex < 0) {
            return;
        }

        this.curRoundDrawerSids.remove(removedIndex);

        if (this.curRoundDrawerSids.isEmpty()) {
            this.curTurnIndex = -1;
            return;
        }

        if (removedIndex < this.curTurnIndex) {
            this.curTurnIndex -= 1;
            return;
        }

        if (removedIndex == this.curTurnIndex) {
            this.curTurnIndex -= 1;
            return;
        }

        if (this.curTurnIndex >= this.curRoundDrawerSids.size()) {
            this.curTurnIndex = this.curRoundDrawerSids.size() - 1;
        }
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, ID_SUFFIX_LENGTH);
    }

    public enum GamePhase {
        STARTING,
        PLAYING,
        GAME_RESULT
    }

    public enum RoundPhase {
        STARTING,
        PLAYING,
        ROUND_RESULT
    }

    public enum TurnPhase {
        STARTING, 
        WORD_CHOICE,
        DRAWING,
        TURN_RESULT
    }
}
