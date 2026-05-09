# 커스텀 워드풀 구현 계획

## 1. 범위
- 이번 작업은 **커스텀 워드풀**만 구현한다.
- 워드풀 타입 선택(기본/역사/과학/나라이름)은 이번 범위에서 제외한다.
- 단어 설명(`description`)은 유지한다.
- 설명 조회 전용 이벤트(`212/213`)는 만들지 않는다.
- `203` 이벤트의 `words` 구조는 변경하지 않는다.
- `DRAWING` 시작 시점 이벤트(208)에서만 선택된 제시어의 `description`을 함께 내려준다.
- `hasDescription` 필드는 만들지 않는다.

## 2. 목표
- 방 설정으로 커스텀 단어 문자열을 받을 수 있어야 한다.
- 커스텀 단어를 `커스텀만` 또는 `기본+커스텀` 모드로 사용할 수 있어야 한다.
- 단어 후보 선택 시 기존 최근 정답 제외 로직을 유지한다.
- drawer가 DRAWING 시작 시점에 선택된 제시어 설명을 확인할 수 있어야 한다.

## 3. 데이터 모델 변경

### 3.1 신규 개념
- `WordEntry`
  - `word: String`
  - `description: String` (nullable)

### 3.2 Setting 확장
- `customWordMode: int`
  - `0`: 커스텀 풀만 사용
  - `1`: 기본 풀 + 커스텀 풀 합치기
  - 기본값: `1` (기본+커스텀)
- `customWordsRaw: String` (nullable)
  - 기본값: `null` (또는 빈 문자열)

### 3.3 Setting payload 확장안
- 기존 8칸 뒤에 2칸 추가:
  - `8`: `customWordMode` (int)
  - `9`: `customWordsRaw` (string or null)
- 개발 단계 기준으로 구버전 호환성은 고려하지 않는다.
- 따라서 `Setting.fromPayload`는 새 포맷(10칸)만 허용한다.

## 4. 커스텀 파싱 규칙

### 4.1 입력 포맷
- `단어|설명,단어,단어|설명`

### 4.2 파싱 절차
- `,`로 split
- 각 토큰 `trim`
- 빈 토큰 제거
- 토큰 내 첫 `|` 기준 분리
  - 좌측: `word` (필수)
  - 우측: `description` (선택, 빈 문자열이면 null)
- `word`가 비어 있으면 항목 폐기
- 중복 단어는 `word` 기준 dedupe(먼저 나온 항목 유지)

### 4.3 예시
- 입력: `원자|물질을 이루는 기본 단위,광합성,관성|운동 상태를 유지하려는 성질`
- 결과:
  - `("원자", "물질을 이루는 기본 단위")`
  - `("광합성", null)`
  - `("관성", "운동 상태를 유지하려는 성질")`

## 5. Provider 설계

### 5.1 원칙
- Room은 커스텀 문자열/모드 설정값만 유지한다. (파싱된 커스텀 풀은 Room에 저장하지 않음)
- 기본 풀(`word-pool.txt`)은 Provider 전역 리소스에서 읽는다.
- 방마다 기본 풀을 복제/보관하지 않는다.
- 커스텀 문자열 파싱(`customWordsRaw -> List<WordEntry>`)은 게임 시작 시 1회 수행하고 Game 필드로 보관한다.

### 5.2 메서드 시그니처(안)
- `pickRandomWords(int count, Set<String> excludedWords, int customWordMode, List<WordEntry> gameCustomPool)`

### 5.3 모드별 최종 후보군
- `mode=0`: 커스텀 풀만
- `mode=1`: 기본 + 커스텀 합집합
- 합집합 중복 기준은 `word`

### 5.4 실패/예외 정책
- 설정 변경(107) 단계에서는 `customWordsRaw` 비어있음을 이유로 거절하지 않는다.
- 설정 변경(107) 단계에서는 `customWordMode` 값 범위(0/1)만 검증한다.
- 최종 후보군이 비어 있으면 단어 선택 단계 진입 차단 + 에러 처리

## 6. 게임 상태 및 이벤트 변경

### 6.1 Game 상태
- `customWordPool`(게임 시작 시 파싱된 커스텀 풀 스냅샷) 필드 추가
- `wordCandidates`를 `List<WordEntry>`로 변경
- `startDrawing(choiceIndex)` 시
  - `answerWord`
  - `answerDescription`
  - 를 함께 확정

### 6.2 203 이벤트(drawer payload)
- `203`의 `words: ["..."]` 구조는 기존 그대로 유지한다.
- description은 203에 포함하지 않는다.

### 6.3 guesser payload
- 기존대로 단어 후보를 보내지 않는다.

### 6.4 턴 시작(208)/정답 처리
- drawer payload에 선택된 단어 설명 필드를 추가한다.
  - 예: `"answerDescription": "..."` (nullable)
- 정답 판정은 기존처럼 `answerWord` 문자열 기준 유지
- description은 정답 판정에 사용하지 않는다.

## 7. 방 설정(107) 처리 흐름
1. 호스트가 setting payload 전송
2. `customWordMode/customWordsRaw` 파싱
3. 모드 유효성 검증
  - `customWordMode`가 `0/1` 범위인지 확인
4. Room에 반영
  - `customWordMode`
  - `customWordsRaw`
5. 참가자에게 setting 변경 브로드캐스트

## 8. 게임 시작 시 커스텀 변환 흐름
1. `startGame`에서 `Room.setting.customWordsRaw`를 읽는다
2. `customWordsRaw`를 `List<WordEntry>`로 변환한다
3. 변환 결과를 `Game.customWordPool` 필드에 저장한다
4. 이후 턴 진행 중에는 `Game.customWordPool`을 재사용한다 (재파싱 없음)

## 9. 시나리오

### 시나리오 A: 커스텀만 사용
1. `customWordMode=0`
2. `customWordsRaw="원자|...,광합성,관성|..."`
3. 게임 시작 시 커스텀 문자열이 `Game.customWordPool`로 변환되어 저장됨
4. 후보 단어는 커스텀에서만 추출
5. 203에서는 기존처럼 단어 목록만 받고, 선택 후 208에서 `answerDescription`을 받는다

### 시나리오 B: 기본+커스텀 사용
1. `customWordMode=1`
2. 기본 풀 + 커스텀 풀 합집합에서 추출
3. 동일 단어가 둘 다 있으면 커스텀 항목 우선(설명 보존 목적)

### 시나리오 C: 잘못된 커스텀 입력
1. `customWordMode=0`, `customWordsRaw=" , |설명,   "`
2. 107 처리는 허용(모드 범위 유효)
3. 게임 시작 시 파싱 결과 `Game.customWordPool`이 비어 있음
4. 이후 단어 선택 시 최종 후보군이 비어 있으면 게임 진행이 중단되고 에러 처리

### 시나리오 D: 기본 풀만 사용(커스텀 미사용)
1. `customWordsRaw`를 비워 둔다
2. `customWordMode`는 기본값 `1`(기본+커스텀)으로 유지한다
3. 커스텀 풀이 비어 있으므로 결과적으로 기본 풀만 동작한다

## 10. 구현 파일
- `src/main/java/io/jhpark/kopic/ge/room/dto/Setting.java`
- `src/main/java/io/jhpark/kopic/ge/room/dto/Game.java`
- `src/main/java/io/jhpark/kopic/ge/room/dto/Room.java`
- `src/main/java/io/jhpark/kopic/ge/room/service/WordPoolProvider.java`
- `src/main/java/io/jhpark/kopic/ge/room/service/DefaultRoomJobFactory.java`
- `src/main/java/io/jhpark/kopic/ge/room/service/RoomSnapshot.java`

## 11. 검증 계획
- 컴파일: `./gradlew compileJava`
- 테스트: `./gradlew test`
- 수동 검증
  - 107로 커스텀 설정 반영되는지
  - 게임 시작 시 `customWordsRaw`가 1회 파싱되어 `Game.customWordPool`에 저장되는지
  - mode 0/1에서 후보군이 의도대로 달라지는지
  - 203 payload의 `words` 구조가 기존과 동일한지
  - 208(drawer) payload에 `answerDescription`이 포함되는지
  - description null 케이스에서 UI/서버 오류 없는지

## 12. 완료 기준
- 커스텀 문자열 파싱/저장이 안정적으로 동작한다.
- 게임 시작 시 커스텀 문자열이 Game 필드로 변환/고정된다.
- mode 0/1에 맞는 후보 추출이 동작한다.
- 203은 기존 구조를 유지하고, 208에서 drawer가 설명을 받을 수 있다.
- 별도 설명 조회 이벤트 없이도 요구 기능이 충족된다.
