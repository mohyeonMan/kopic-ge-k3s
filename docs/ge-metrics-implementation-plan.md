# GE 서버 메트릭 구현 계획

## 1. 목표
- GE 서버의 요청량, 처리속도, 실패율, 게임 흐름 품질을 Prometheus에서 즉시 관찰 가능하게 만든다.
- 운영 대응 우선순위(성능/장애 탐지)를 기준으로 메트릭을 단계적으로 추가한다.
- 라벨 카디널리티를 통제해 장기 운영 시에도 저장소/쿼리 비용이 폭증하지 않게 한다.

## 2. 범위
- 이번 계획은 아래 16개 메트릭 전부를 구현 대상으로 한다.
- 메트릭은 모두 Pull 방식(Prometheus scrape)으로 수집한다.

## 3. 우선순위 메트릭 목록

| 우선순위 | 메트릭명 | 타입 | 라벨 | 목적 |
|---|---|---|---|---|
| P1 | `kopic_ge_inbound_events_total` | Counter | `event_code` | 요청수/RPS |
| P1 | `kopic_ge_room_job_duration_seconds` | Timer(Histogram) | `job` | 처리속도, p95/p99 |
| P1 | `kopic_ge_room_submit_total` | Counter | `result`,`reason` | 수락/거절율 |
| P1 | `kopic_ge_inbound_rejected_total` | Counter | `reason` | 검증 실패율 |
| P1 | `kopic_ge_room_mailbox_wait_duration_seconds` | Timer(Histogram) | `job` | 큐 대기시간 |
| P2 | `kopic_ge_outbound_publish_failures_total` | Counter | `reason` | 발행 실패 감지 |
| P2 | `kopic_ge_outbound_events_total` | Counter | `event_code` | 응답량 |
| P2 | `kopic_ge_rooms_active` | Gauge | `room_type` | 활성 룸 수 |
| P2 | `kopic_ge_participants_total` | Gauge | `room_type` | 참가자 수 |
| P3 | `kopic_ge_turn_end_total` | Counter | `reason` | 턴 종료 사유 비율 |
| P3 | `kopic_ge_word_choice_total` | Counter | `mode` | 수동/자동 선택 비율 |
| P3 | `kopic_ge_guess_total` | Counter | `result` | 정답률 추적 |
| P3 | `kopic_ge_timer_scheduled_total` | Counter | `timer_key` | 타이머 예약량 |
| P3 | `kopic_ge_timer_cancelled_total` | Counter | `timer_key` | 타이머 취소량 |
| P3 | `kopic_ge_game_start_total` | Counter | `room_type`,`trigger` | 게임 시작 패턴 |
| P3 | `kopic_ge_hint_reveal_total` | Counter | 없음 | 힌트 공개량 |

## 4. 메트릭/라벨 확정 스펙(v1)

### 4.1 공통 규칙
- 라벨 값은 기본적으로 소문자 snake_case로 정규화한다.
- 예외: `event_code`는 숫자 문자열, `timer_key`는 코드 상수 문자열을 그대로 사용한다.
- 허용값 외 입력이 들어오면 `unknown` 또는 `other`로 집계한다.
- 클라이언트 입력값을 라벨에 직접 넣지 않는다.

### 4.2 메트릭별 라벨/허용값
- `kopic_ge_inbound_events_total{event_code}`
  - `event_code`: `101`,`102`,`103`,`107`,`200`,`201`,`203`,`204`,`other`
  - 규칙: 지원하지 않는 코드/이상값은 `other`로 집계
- `kopic_ge_room_job_duration_seconds{job}`
  - `job`: `join`,`leave`,`start_game`,`explicit_word_choice`,`draw_stroke`,`guess_chat`,`update_setting`,`next_round`,`next_turn`,`open_word_choice`,`word_choice_timeout`,`drawing_timeout`,`hint_reveal_tick`,`turn_end`,`turn_result_end`,`game_end`,`game_result_end`,`return_to_lobby`,`close_if_empty`,`unknown`
- `kopic_ge_room_submit_total{result,reason}`
  - `result`: `accepted`,`rejected`
  - `reason`: `none`,`invalid_request`,`room_not_found`,`mailbox_full`,`actor_inactive`,`unknown`
  - 규칙: `accepted`일 때 `reason=none` 고정
- `kopic_ge_inbound_rejected_total{reason}`
  - `reason`: `invalid_request`,`unsupported_event`,`forbidden`,`room_full`,`conflict`,`missing_sender_session_id`,`missing_ws_node_id`,`missing_envelope`,`deserialize_failed`,`unknown`
- `kopic_ge_room_mailbox_wait_duration_seconds{job}`
  - `job`: `kopic_ge_room_job_duration_seconds`와 동일 집합 사용
- `kopic_ge_outbound_publish_failures_total{reason}`
  - `reason`: `invalid_target`,`serialize_failed`,`publish_exception`,`unknown`
- `kopic_ge_outbound_events_total{event_code}`
  - `event_code`: `107`,`200`,`201`,`202`,`203`,`204`,`205`,`206`,`207`,`208`,`209`,`210`,`211`,`301`,`302`,`408`,`1999`,`other`
- `kopic_ge_rooms_active{room_type}`
  - `room_type`: `quick`,`private`
- `kopic_ge_participants_total{room_type}`
  - `room_type`: `quick`,`private`
- `kopic_ge_turn_end_total{reason}`
  - `reason`: `drawing_timeout`,`drawer_left`,`first_correct`,`all_correct`,`other`
- `kopic_ge_word_choice_total{mode}`
  - `mode`: `explicit`,`timeout_auto_pick`
- `kopic_ge_guess_total{result}`
  - `result`: `correct`,`wrong`
- `kopic_ge_timer_scheduled_total{timer_key}`
  - `timer_key`: `close-if-empty`,`game:start-round`,`game:next-turn`,`game:open-word-choice`,`game:word-choice`,`game:drawing-phase:timeout`,`game:drawing-phase:hint`,`game:turn-result`,`game:game-result`,`game:quick-restart`,`other`
- `kopic_ge_timer_cancelled_total{timer_key}`
  - `timer_key`: `close-if-empty`,`game:*`,`game:drawing-phase:*`,`game:word-choice`,`game:quick-restart`,`other`
- `kopic_ge_game_start_total{room_type,trigger}`
  - `room_type`: `quick`,`private`
  - `trigger`: `request`,`system`
  - 규칙: `sessionId`가 비어있으면 `system`, 아니면 `request`
- `kopic_ge_hint_reveal_total`
  - 라벨 없음

## 5. 라벨 정책
- 금지 라벨: `roomId`, `sessionId`, `turnId`, `gameId` (고카디널리티)
- 허용 라벨: `event_code`, `reason`, `job`, `room_type`, `mode`, `timer_key`, `trigger`
- `timer_key`는 코드 상수값 집합으로 제한한다. 임의 문자열 라벨 금지.

## 6. 구현 포인트(파일 단위)
- `build.gradle`
  - `spring-boot-starter-actuator`, `micrometer-registry-prometheus` 추가
  - 필요 시 `spring-boot-starter-web` 추가(스크랩 엔드포인트 노출용)
- `src/main/resources/application.yaml`
  - `management.endpoints.web.exposure.include=health,prometheus`
  - `management.prometheus.metrics.export.enabled=true`
  - 필요 시 `management.server.port` 분리
- `src/main/java/io/jhpark/kopic/ge/common/metrics/GeMetrics.java` (신규)
  - Counter/Timer/Gauge 등록 및 공통 기록 API 제공
- `src/main/java/io/jhpark/kopic/ge/inbound/listener/WsEventSubscriber.java`
  - inbound 이벤트 수, 역직렬화 실패 카운트 기록
- `src/main/java/io/jhpark/kopic/ge/inbound/handler/DefaultEventHandler.java`
  - 입력 검증 거절(`reason`) 카운트 기록
- `src/main/java/io/jhpark/kopic/ge/room/service/DefaultRoomRunner.java`
  - submit 결과(`accepted/rejected`) 카운트
  - job 실행 시간 Timer
  - follow-up 타이머 예약/취소 카운트
- `src/main/java/io/jhpark/kopic/ge/room/dto/RoomSession.java`
  - mailbox 대기시간 측정용 enqueue 시각 보관 구조 추가
- `src/main/java/io/jhpark/kopic/ge/room/service/RoomJob.java`
  - `job` 라벨 기록을 위한 작업명 필드(`jobName`) 추가
- `src/main/java/io/jhpark/kopic/ge/room/service/DefaultRoomService.java`
  - game start trigger 카운트(수동/자동 구분 정보 전달)
- `src/main/java/io/jhpark/kopic/ge/room/service/DefaultRoomJobFactory.java`
  - word choice mode, turn end reason, guess result, hint reveal 카운트
- `src/main/java/io/jhpark/kopic/ge/room/registry/RoomSessionStore.java`
  - gauge 계산용 스냅샷 조회 메서드 추가
- `src/main/java/io/jhpark/kopic/ge/room/registry/InMemoryRoomSessionStore.java`
  - room_type별 active room / participants 집계 구현

## 7. 단계별 구현 절차

### 7.1 1단계: 메트릭 인프라/노출
- 의존성 추가
- `/actuator/prometheus` 노출 설정
- 로컬에서 엔드포인트 확인

### 7.2 2단계: 트래픽/지연(P1) 계측
- `inbound_events_total`
- `room_job_duration_seconds`
- `room_submit_total`
- `inbound_rejected_total`
- `room_mailbox_wait_duration_seconds`

### 7.3 3단계: 발행/상태(P2) 계측
- `outbound_publish_failures_total`
- `outbound_events_total`
- `rooms_active`
- `participants_total`

### 7.4 4단계: 게임 흐름 품질(P3) 계측
- `turn_end_total`, `word_choice_total`, `guess_total`
- `timer_scheduled_total`, `timer_cancelled_total`
- `game_start_total`, `hint_reveal_total`

### 7.5 5단계: 운영 쿼리/알림 기본셋
- 요청량: `sum(rate(kopic_ge_inbound_events_total[1m]))`
- 처리속도 p95:
  - `histogram_quantile(0.95, sum(rate(kopic_ge_room_job_duration_seconds_bucket[5m])) by (le, job))`
- mailbox 지연 p95:
  - `histogram_quantile(0.95, sum(rate(kopic_ge_room_mailbox_wait_duration_seconds_bucket[5m])) by (le, job))`
- 거절율:
  - `sum(rate(kopic_ge_room_submit_total{result="rejected"}[5m])) / sum(rate(kopic_ge_room_submit_total[5m]))`
- 발행 실패율:
  - `sum(rate(kopic_ge_outbound_publish_failures_total[5m])) / sum(rate(kopic_ge_outbound_events_total[5m]))`

## 8. 검증 계획
- 빌드 검증: `./gradlew compileJava`
- 테스트 검증: `./gradlew test`
- 수동 검증
  - `/actuator/prometheus`에서 신규 메트릭 노출 확인
  - 이벤트 전송 후 Counter 증가 확인
  - 부하 상황에서 p95 지표 갱신 확인
  - room 생성/입퇴장 시 Gauge 실시간 반영 확인

## 9. 완료 기준
- 계획된 16개 메트릭이 모두 노출된다.
- 고카디널리티 라벨이 없다.
- 핵심 운영 쿼리(요청량/지연/거절율/발행실패율)가 정상 동작한다.
- 컴파일/테스트가 모두 통과한다.
