package io.jhpark.kopic.ge.room.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
    private Map<String, Integer> totalPoints;
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
    private List<String> words;
    private String answerWord;
    private String curDrawerSid;
    private Map<String, Integer> earnedPoints;

    public static Game start(Setting gameSetting, List<String> participantSids) {
        Objects.requireNonNull(gameSetting, "gameSetting");
        Objects.requireNonNull(participantSids, "participantSids");
        if (participantSids.isEmpty()) {
            throw new IllegalArgumentException("participantSids must not be empty");
        }

        Game game = new Game();
        game.gameId = newId(GAME_ID_PREFIX);
        game.gameSetting = gameSetting.copy();
        game.gamePhase = GamePhase.PLAYING;
        game.totalPoints = new HashMap<>();
        game.startedAt = Instant.now();

        game.curRoundIndex = 0;
        game.curRoundId = null;
        game.roundPhase = RoundPhase.STARTING;
        game.curRoundDrawerSids = List.of();

        game.curTurnId = newId(TURN_ID_PREFIX);
        game.curTurnIndex = 0;
        game.turnPhase = TurnPhase.STARTING;
        game.words = List.of();
        game.answerWord = null;
        game.curDrawerSid = null;
        game.earnedPoints = new HashMap<>();

        for (String participantSid : participantSids) {
            game.totalPoints.put(participantSid, 0);
        }
        return game;
    }

    public void startRound(int roundNo, List<String> roundDrawerSids) {
        if (roundNo <= 0) {
            throw new IllegalArgumentException("roundNo must be positive");
        }
        Objects.requireNonNull(roundDrawerSids, "roundDrawerSids");
        if (roundDrawerSids.isEmpty()) {
            throw new IllegalArgumentException("roundDrawerSids must not be empty");
        }

        this.curRoundIndex = roundNo;
        this.curRoundId = newId(ROUND_ID_PREFIX);
        this.roundPhase = RoundPhase.PLAYING;
        this.curRoundDrawerSids = List.copyOf(roundDrawerSids);

        this.curTurnId = newId(TURN_ID_PREFIX); 
        this.curTurnIndex = 0;
        this.turnPhase = TurnPhase.STARTING;
        this.words = List.of();
        this.answerWord = null;
        this.curDrawerSid = this.curRoundDrawerSids.get(this.curTurnIndex);
        this.earnedPoints = new HashMap<>();

        for (String participantSid : this.curRoundDrawerSids) {
            this.totalPoints.putIfAbsent(participantSid, 0);
        }
    }

    public void startTurn(int turnIndex) {
        if (this.curRoundDrawerSids == null || this.curRoundDrawerSids.isEmpty()) {
            throw new IllegalStateException("curRoundDrawerSids must not be empty");
        }
        if (turnIndex < 0 || turnIndex >= this.curRoundDrawerSids.size()) {
            throw new IllegalArgumentException("turnIndex out of range");
        }

        // 새 턴은 단어 선택 전 상태를 초기화한 뒤 시작한다.
        this.curTurnIndex = turnIndex;
        this.curTurnId = newId(TURN_ID_PREFIX);
        this.turnPhase = TurnPhase.STARTING;
        this.curDrawerSid = this.curRoundDrawerSids.get(turnIndex);
        this.words = List.of();
        this.answerWord = null;
        this.earnedPoints = new HashMap<>();
    }

    public void openWordChoice(List<String> words) {
        Objects.requireNonNull(words, "words");
        if (words.isEmpty()) {
            throw new IllegalArgumentException("words must not be empty");
        }
        // 재시도된 잡에서도 같은 턴에서 단어 선택 창을 다시 열 수 있게 허용한다.
        if (this.turnPhase != TurnPhase.STARTING && this.turnPhase != TurnPhase.WORD_CHOICE) {
            throw new IllegalStateException("turnPhase must be STARTING or WORD_CHOICE");
        }

        this.words = List.copyOf(words);
        this.answerWord = null;
        this.turnPhase = TurnPhase.WORD_CHOICE;
    }

    public void startDrawing(int choiceIndex) {
        if (this.turnPhase != TurnPhase.WORD_CHOICE) {
            throw new IllegalStateException("turnPhase must be WORD_CHOICE");
        }
        if (this.words == null || this.words.isEmpty()) {
            throw new IllegalStateException("words must not be empty");
        }
        if (choiceIndex < 0 || choiceIndex >= this.words.size()) {
            throw new IllegalArgumentException("choiceIndex out of range");
        }

        // 단어가 선택되면 정답을 확정하고 DRAWING 단계로 진입한다.
        this.answerWord = this.words.get(choiceIndex);
        this.turnPhase = TurnPhase.DRAWING;
    }

    public void finishTurnResult() {
        this.turnPhase = TurnPhase.TURN_RESULT;
    }

    public boolean hasNextTurn() {
        if (this.curRoundDrawerSids == null || this.curRoundDrawerSids.isEmpty()) {
            return false;
        }
        // 턴 인덱스는 현재 라운드의 순서 리스트 기준 0부터 시작한다.
        return this.curTurnIndex + 1 < this.curRoundDrawerSids.size();
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

        this.totalPoints.remove(sessionId);
        this.earnedPoints.remove(sessionId);

        curRoundDrawerSids.remove(sessionId);
        
        // if (this.curRoundDrawerSids == null || this.curRoundDrawerSids.isEmpty()) {
        //     return;
        // }

        
        // int removedIndex = this.curRoundDrawerSids.indexOf(sessionId);
        // if (removedIndex < 0) {
        //     return;
        // }


        // List<String> nextDrawerSids = new ArrayList<>(this.curRoundDrawerSids);
        // nextDrawerSids.remove(removedIndex);
        // this.curRoundDrawerSids = List.copyOf(nextDrawerSids);

        // if (this.curRoundDrawerSids.isEmpty()) {
        //     this.curDrawerSid = null;
        //     this.curTurnIndex = 0;
        //     return;
        // }

        // if (sessionId.equals(this.curDrawerSid)) {
        //     int nextTurnIndex = Math.min(removedIndex, this.curRoundDrawerSids.size() - 1);
        //     this.curTurnIndex = Math.max(0, nextTurnIndex);
        //     this.curDrawerSid = this.curRoundDrawerSids.get(this.curTurnIndex);
        //     return;
        // }

        // if (removedIndex < this.curTurnIndex) {
        //     this.curTurnIndex = this.curTurnIndex - 1;
        // }
        // if (this.curTurnIndex >= this.curRoundDrawerSids.size()) {
        //     this.curTurnIndex = this.curRoundDrawerSids.size() - 1;
        // }
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
