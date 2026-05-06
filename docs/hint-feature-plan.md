# 힌트 기능 구현 계획

## 1. 목표
- DRAWING 시작과 동시에 `drawing-timeout` + `hint-reveal` 타이머를 함께 예약한다.
- 설정값 `hintRevealSec`, `hintLetterCount`를 실제 동작에 연결한다.
- 단어는 점진적으로 공개하되 **최소 1글자는 항상 미공개** 상태를 유지한다.

## 2. 상태 모델
- `Game`에 힌트 상태를 추가한다.
  - `hintPattern`: 현재 공개 패턴
  - `pendingHintIndexes`: 앞으로 공개할 문자 인덱스 큐(랜덤 순서)
  - `hintTotalRevealCount`: 이 턴에서 공개 가능한 총 글자 수
  - `hintRevealedCount`: 현재까지 공개한 글자 수
- 턴 시작/그리기 시작 시 힌트 상태를 초기화한다.

## 3. 공개 정책
- 공개 대상 문자는 `Character.isLetterOrDigit(ch)` 기준으로 계산한다.
- 공백/기호는 처음부터 원문 그대로 노출한다.
- 공개 가능한 총 글자 수는 `max(0, revealableCount - 1)`로 제한한다.
  - 1글자 정답이면 공개 가능 수는 0개다.
- `hintLetterCount`만큼 주기적으로 공개하되, 남은 수보다 크면 남은 만큼만 공개한다.

## 4. 타이머/이벤트 정책
- DRAWING 시작 시 follow-up 2개를 동시에 예약한다.
  - `game:drawing-phase:timeout`
  - `game:drawing-phase:hint`
- 턴 종료 시 `game:drawing-phase:*` 프리픽스 취소로 관련 타이머를 일괄 정리한다.
- 힌트 이벤트 코드 `211`을 추가하고, drawer를 제외한 참가자에게만 전송한다.

## 5. 스냅샷 동기화
- `RoomSnapshot.GameSnapshot`에 `hintPattern` 필드를 추가한다.
- DRAWING 중인 룸 스냅샷 요청 시 현재 힌트 상태가 포함되게 한다.

## 6. 검증
- `./gradlew compileJava`
- `./gradlew test`
- 수동 점검 포인트
  - DRAWING 시작 직후 guesser가 초기 `hintPattern`을 받는지
  - 설정 주기마다 힌트가 증가하는지
  - 1글자 정답에서 힌트가 공개되지 않는지
  - 턴 종료 시 힌트 타이머가 남지 않는지
