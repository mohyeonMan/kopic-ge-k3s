# 오토스케일링 / DRAIN 정책 정리

## 0. 기본 전제

현재 구조에서는 GE를 다른 GE로 이전시키지 않는다.

scale-in 또는 Pod 종료가 발생하면 종료 대상 Pod가 `SIGTERM`을 받고, 각 서버가 자기 책임 범위 안에서 DRAIN 처리를 수행한다.

```text
GE DRAIN = game drain
- 방 / 게임을 정리하는 정책
- 새 방 생성과 quick 매칭 유입을 막음
- 기존 게임은 최대 보호 시간 안에서 마무리
- 게임 중이 아닌 방은 일정 시간 뒤 삭제

WS DRAIN = connection drain
- WebSocket 연결을 정리하는 정책
- 새 WebSocket 연결을 막음
- 기존 연결자에게 재연결 안내를 보냄
- 재연결 시점 판단은 클라이언트에 위임
```

`terminationGracePeriodSeconds`는 GE와 WS 모두 다음 값을 기준으로 둔다.

```yaml
terminationGracePeriodSeconds: 3900
```

```text
3900초의 의미

3600초
- 일반적인 게임이 자연스럽게 종료될 수 있도록 보호하는 시간

300초
- 종료 안내
- Redis 정리
- RabbitMQ 정리
- WebSocket close
- 기타 종료 버퍼
```

이 값은 게임의 이론상 최대 시간을 모두 보장하기 위한 값이 아니다.  
일반적인 플레이 패턴을 기준으로 둔 운영상 최대 보호 시간이다.

---

## 1. GE scale-in 정책

GE가 scale-in 또는 Pod 종료로 `SIGTERM`을 받으면 즉시 DRAIN 상태로 전환한다.

```text
ACTIVE → DRAIN
```

GE DRAIN의 목적은 다음이다.

```text
- 새 방이 더 이상 생성되지 않게 한다.
- quick 매칭 후보에서 즉시 빠진다.
- 기존 게임은 가능한 한 마무리한다.
- 게임 중이 아닌 방은 일정 시간 뒤 삭제한다.
- 모든 room이 정리되면 graceful period를 기다리지 않고 즉시 종료한다.
```

GE DRAIN 진입 시 반드시 수행해야 하는 작업은 다음이다.

```text
1. ge:{geId}.status = DRAIN 으로 갱신
2. ge:{geId} heartbeat TTL 계속 갱신
3. ge:load에서 자기 geId 제거
4. quick:available에서 자기 geId 후보 즉시 제거
5. 새 private room 생성 차단
6. 새 quick room 생성 차단
7. quick join 차단
8. 기존 private roomCode 입장은 허용
9. 보유 중인 room을 상태별로 정리
10. activeRoomCount == 0 이면 즉시 정상 종료
11. graceful period 만료 전까지 남은 room은 강제 종료 후 정리
```

중요한 점은 DRAIN 중에도 `ge:{geId}` heartbeat를 유지해야 한다는 것이다.

```text
ge:{geId} = DRAIN
TTL 계속 갱신
```

GE는 아직 살아서 기존 게임과 방을 정리하고 있다.  
TTL이 끊기면 lobby나 다른 서버가 GE가 죽었다고 오판할 수 있다.

---

## 2. GE DRAIN 라우팅 정책

DRAIN 상태에서 막아야 하는 것은 “모든 입장”이 아니다.

정확히는 다음 흐름을 막는다.

```text
DRAIN 상태에서 차단

- 새 private room 생성
- 새 quick room 생성
- quick join
- quick:available 후보 노출
```

DRAIN 상태에서도 다음은 허용한다.

```text
DRAIN 상태에서 허용

- 이미 생성된 private roomCode를 통한 기존 방 입장
- 기존 연결자 유지
- 기존 진행 중 게임 유지
```

즉, DRAIN은 “기존 방 입장 차단” 상태가 아니라, **새 방 생성과 quick 매칭 후보 노출을 차단하는 상태**다.

quick 계열을 막는 이유는 quick join 과정에서 새로운 방이 생성될 수 있고, DRAIN 중인 GE가 quick 후보로 남아 있으면 종료 대상 서버로 매칭 유입이 계속 발생할 수 있기 때문이다.

반면 private roomCode 입장은 이미 존재하는 특정 방으로 들어오는 흐름이다.  
새 방을 만드는 것이 아니므로 DRAIN 상태에서도 허용한다.

정책은 다음과 같이 고정한다.

```text
GE DRAIN 상태

- 새 private room 생성: 금지
- 새 quick room 생성: 금지
- quick join: 금지
- quick:available 후보: 즉시 제거
- 기존 private roomCode 입장: 허용
- 기존 연결자: 유지
- 기존 진행 중 게임: 최대 3600초 보호
```

---

## 3. GE DRAIN 시 room 상태별 처리

GE가 DRAIN으로 전환되면 자신이 가진 모든 room을 순회한다.

room 상태에 따라 처리 방식이 달라야 한다.

### 3-1. IN_GAME room

게임이 진행 중인 방은 바로 삭제하지 않는다.

```text
IN_GAME room 처리

1. SERVER_DRAIN_NOTICE 발송
2. 현재 게임은 계속 진행
3. 새 라운드 시작 여부는 기존 게임 정책에 따름
4. 게임 종료 로직에서 현재 GE 상태를 확인
5. geStatus == DRAIN 이면 방 삭제 및 로비 이동 처리
6. geStatus == ACTIVE 이면 기존 정상 게임 종료 흐름 수행
```

별도의 `drainAfterGame` 같은 room별 플래그는 사용하지 않는다.

게임 종료 시점에 현재 GE 상태를 확인한다.

```text
onGameEnd(room):

if geStatus == DRAIN:
    broadcast SERVER_DRAIN_FINAL
    deleteRoom(room)
    moveUsersToLobby(room)
    requestWsCloseIfNeeded(room)
    return

normalGameEnd(room)
```

절대 놓치면 안 되는 점은 다음이다.

```text
게임 종료 시점에 반드시 geStatus를 다시 확인한다.
DRAIN 진입 시점에만 판단하고 끝내면 안 된다.
```

DRAIN 진입 후 게임이 끝났다면 해당 방은 정리 대상이다.

---

### 3-2. WAITING / READY / IDLE room

게임 중이 아닌 방은 게임 종료 이벤트가 발생하지 않는다.  
따라서 별도의 삭제 스케줄이 필요하다.

```text
WAITING / READY / IDLE room 처리

1. 새 게임 시작 차단
2. ROOM_DRAIN_DELETE_SCHEDULED 발송
3. deleteAt = now + 5분 설정
4. room mailbox에 삭제 스케줄 등록
5. 5분 뒤 SERVER_DRAIN_FINAL 발송
6. 방 삭제
7. 사용자 로비 이동
8. 필요 시 WS close 요청
```

이 상태의 방에서 절대 허용하면 안 되는 것은 새 게임 시작이다.

```text
DRAIN + WAITING/READY/IDLE room

- private roomCode 입장: 허용 가능
- 새 게임 시작: 금지
- quick 후보 등록: 금지
- 5분 후 삭제: 유지
```

private roomCode로 새 사용자가 들어오더라도 해당 방이 삭제 예정이라는 상태는 유지된다.

입장 응답(e:304) 에는 내부 상태를 내려줄 수 있다.

사용자에게 반드시 기술적으로 노출할 필요는 없지만, 클라이언트는 이 값을 보고 새 게임 시작 버튼을 비활성화할 수 있어야 한다.


---

### 3-3. EMPTY room

참여자가 없는 방은 즉시 삭제한다.

```text
EMPTY room 처리

1. roomCode 제거
2. quick 후보 제거
3. room 삭제
4. activeRoomCount 갱신
```

---

### 3-4. RESULT / GAME_ENDED room

이미 게임이 끝났고 결과 화면만 남은 방은 짧은 유예 후 삭제한다.

```text
RESULT / GAME_ENDED room 처리

1. SERVER_DRAIN_FINAL 또는 ROOM_CLOSE_SCHEDULED 발송
2. 짧은 유예 시간 제공
   예: 30초 ~ 60초
3. 방 삭제
4. 사용자 로비 이동
```

---

## 4. Redis directory 정책

Redis directory는 다음 구조를 기준으로 한다.

```text
ge:{geId}
- GE 상태
- ACTIVE / DRAIN
- TTL 기반 heartbeat

ge:load
- 활성 GE의 부하 정보
- 새 방 생성 후보 판단에 사용

quick:available
- quick join 대상 ZSET
- member = {geId}:{roomId}
- score = joinedAvailableAt

quick:ge:{geId}:rooms
- 특정 GE가 quick 후보로 올린 roomId 목록
- SET
- member = {roomId}

roomCode:{roomCode}
- private roomCode가 위치한 geId
```

GE DRAIN 진입 시 Redis 정리 정책은 다음이다.

```text
ge:{geId}
- 삭제하지 않음
- status = DRAIN 으로 유지
- TTL heartbeat 계속 갱신

ge:load
- 자기 geId 제거

quick:available
- 자기 geId에 속한 후보 즉시 제거

quick:ge:{geId}:rooms
- 자기 quick 후보 제거 후 삭제

roomCode:{roomCode}
- DRAIN 진입 시 즉시 삭제하지 않음
```

`roomCode:{roomCode}`를 DRAIN 진입 시 삭제하지 않는 이유는 기존 private roomCode 입장을 허용하기 때문이다.

roomCode 조회 흐름은 다음과 같다.

```text
roomCode:{roomCode} 조회
→ geId 확인
→ ge:{geId} ACTIVE
→ 정상 입장

roomCode:{roomCode} 조회
→ geId 확인
→ ge:{geId} DRAIN
→ 기존 private roomCode 입장 허용
→ 단, geStatus=DRAIN / roomDrain=true 전달 가능

roomCode:{roomCode} 조회
→ geId 확인
→ ge:{geId} 없음
→ 입장 실패 또는 종료 안내
```

---

## 5. quick:available 정리 방식

`quick:available`은 ZSET이다.

```text
quick:available
ZSET
member = {geId}:{roomId}
score = joinedAvailableAt
```

DRAIN 중에는 quick join이 금지되므로, GE는 자기 후보를 즉시 제거해야 한다.

특정 geId 후보를 빠르게 제거하기 위해 보조 인덱스를 둔다.

```text
quick:ge:{geId}:rooms
SET
member = {roomId}
```

quick 후보 등록 시:

```text
ZADD quick:available {score} {geId}:{roomId}
SADD quick:ge:{geId}:rooms {roomId}
```

quick 후보 제거 시:

```text
ZREM quick:available {geId}:{roomId}
SREM quick:ge:{geId}:rooms {roomId}
```

DRAIN 진입 시:

```text
SMEMBERS quick:ge:{geId}:rooms
각 roomId에 대해 ZREM quick:available {geId}:{roomId}
DEL quick:ge:{geId}:rooms
```

이 정리는 GE가 자기 책임으로 수행한다.

절대 놓치면 안 되는 점은 다음이다.

```text
DRAIN 진입 후 quick:available에 자기 후보가 남아 있으면 안 된다.
```

---

## 6. lobby 정책

lobby는 Kubernetes replica를 직접 수정하지 않는다.  
lobby의 역할은 Redis directory를 보고 적절한 GE를 안내하는 것이다.

lobby는 다음 정책을 따른다.

```text
새 private room 생성
- ge:load 조회
- ge:{geId}.status 확인
- ACTIVE GE만 후보로 사용
- DRAIN GE는 제외

새 quick room 생성
- ACTIVE GE만 후보로 사용
- DRAIN GE는 제외

quick join
- quick:available에서 후보 조회
- 후보의 geId 상태 확인
- DRAIN GE 후보는 사용하지 않음
- DRAIN 후보가 발견되면 제거 또는 skip

private roomCode 입장
- roomCode:{roomCode}로 geId 조회
- ge:{geId} 상태 확인
- ACTIVE면 정상 입장
- DRAIN이어도 기존 방 입장은 허용
- geId가 없으면 입장 실패 또는 종료 안내
```

lobby가 절대 놓치면 안 되는 점은 다음이다.

```text
DRAIN GE를 새 방 생성 후보로 쓰면 안 된다.
DRAIN GE를 quick join 후보로 쓰면 안 된다.
private roomCode 입장만 예외적으로 허용한다.
```

---

## 7. WS scale-in 정책

WS scale-in은 GE scale-in과 의미가 다르다.

```text
WS DRAIN = connection drain
GE DRAIN = game drain
```

WS는 게임 상태를 판단하지 않는다.  
WS는 클라이언트에게 현재 WS가 종료 예정임을 알리고, 재연결 책임은 클라이언트에 둔다.

WS가 `SIGTERM`을 받으면 다음을 수행한다.

```text
1. WS 상태를 DRAIN으로 전환
2. readiness false 처리
3. 새 WebSocket handshake 차단
4. 기존 client에게 WS_DRAIN_NOTICE 발송
5. 기존 연결은 즉시 끊지 않음
6. 최대 3900초 안에서 유지
7. close 발생 시 클라이언트 fallback 재연결
8. 정상 종료 시 GE에 leave/disconnect 이벤트 전송
```

WS가 하지 않는 일은 다음이다.

```text
- GE에 세션별 재연결 가능 여부를 주기적으로 묻지 않는다.
- client 상태를 대신 판단하지 않는다.
- 게임 중인 client를 임의로 새 WS에 붙이지 않는다.
```

WS는 가벼운 연결 서버로 유지한다.

---

## 8. 클라이언트 재연결 정책

클라이언트는 서버 종료 관련 이벤트를 받으면 내부 상태를 갱신한다.

권장 상태값은 다음과 같다.

```ts
type ServerDrainState = {
  wsDrain: boolean;
  geDrain: boolean;
  nextAction:
    | "NONE"
    | "RECONNECT_AFTER_CLOSE"
    | "LEAVE_AFTER_GAME"
    | "FORCE_LEAVE";
};
```

이때 우선순위는 다음과 같다.

```text
GE_DRAIN / ROOM_CLOSE / GAME_FORCE_CLOSE
> WS_DRAIN_NOTICE
> 일반 network close
```

### WS_DRAIN_NOTICE 수신

WS_DRAIN은 연결 서버가 종료 예정이라는 참고 상태다.  
즉시 새 WS로 연결하라는 뜻이 아니다.

```text
WS_DRAIN_NOTICE 수신

- wsDrain = true
- GE_DRAIN 상태가 이미 있으면 사용자 행동은 변경하지 않음
- 기존 연결은 유지
- 실제 close가 발생하면 fallback 재연결
```

### GE_DRAIN_NOTICE / SERVER_DRAIN_FINAL 수신

GE_DRAIN은 방 또는 게임이 정리되는 신호다.  
반드시 수용한다.

```text
GE_DRAIN 계열 이벤트 수신

- geDrain = true
- WS_DRAIN보다 우선
- 게임 중이면 게임 종료 후 로비 이동
- 강제 종료 이벤트면 즉시 로비 이동
- 이때 wsDrain=true라고 해서 즉시 새 WS에 연결하지 않음
- 기존 WS가 살아 있으면 그대로 사용
- 이후 WS close가 발생하면 fallback 재연결
```

정책적으로 중요한 점은 다음이다.

```text
WS_DRAIN은 재연결 참고값이다.
GE_DRAIN은 방/게임 종료 명령이다.
```

따라서 둘이 동시에 발생하면 GE_DRAIN이 우선한다.

```text
이미 GE_DRAIN 상태이면 이후 WS_DRAIN_NOTICE는 사용자 행동을 바꾸지 않는다.
이미 WS_DRAIN 상태였더라도 GE_DRAIN이 오면 GE_DRAIN 정책으로 덮어쓴다.
```

---

## 9. WS와 GE가 동시에 scale-in 되는 경우

동시에 scale-in이 발생하면 클라이언트가 두 종류의 이벤트를 받을 수 있다.

```text
WS_DRAIN_NOTICE
- 현재 연결 서버가 종료 예정

GE_DRAIN_NOTICE / SERVER_DRAIN_FINAL
- 현재 방 또는 게임 서버가 종료 예정
```

동시 발생 시 정책은 다음이다.

```text
1. GE_DRAIN이 있으면 GE_DRAIN이 우선한다.
2. 사용자는 방/게임 종료 흐름을 따른다.
3. WS_DRAIN은 내부 참고값으로만 유지한다.
4. 기존 WS가 살아 있으면 그대로 둔다.
5. 실제 WS close가 발생하면 fallback 재연결한다.
```

사용자에게는 WS와 GE를 모두 설명하지 않는다.  
사용자 안내 기준은 “현재 방/게임이 유지되는가”다.

```text
WS만 DRAIN
- 게임은 유지된다.
- 연결 서버가 종료될 수 있으므로 close 시 재연결한다.

GE DRAIN
- 현재 방 또는 게임이 정리된다.
- 게임 종료 후 또는 강제 종료 시 로비로 이동한다.

WS + GE DRAIN
- GE DRAIN 안내만 사용자에게 우선 노출한다.
- WS DRAIN은 내부 연결 참고값으로 처리한다.
```

---

## 10. RabbitMQ 처리

GE는 DRAIN 상태에서도 기존 게임과 방을 정리해야 한다.  
따라서 RabbitMQ consumer를 바로 닫으면 안 된다.

```text
GE DRAIN 진입

- RabbitMQ consumer 유지
- 기존 room/game 이벤트 처리 유지
- 기존 private roomCode 입장 처리 유지
- 새 방 생성/quick 계열 요청은 거부
```

room 종료 시:

```text
- 사용자에게 종료/로비 이동 이벤트 발송
- 필요 시 WS에 close 요청
- roomCode 정리
- quick 후보 정리
- activeRoomCount 갱신
```

최종 종료 시:

```text
- 남은 room 강제 종료
- 남은 메시지 처리 또는 폐기 정책 적용
- consumer close
- publisher close
- Redis 정리
- 프로세스 종료
```

죽은 GE나 WS로 stale 메시지가 오래 쌓이지 않도록 queue 정책도 필요하다.

```text
권장
- queue auto-delete
- message TTL
- dead-letter 정책
```

---

## 11. 종료 이벤트 정의

### WS_DRAIN_NOTICE

WS가 DRAIN 진입 시 client에게 보내는 이벤트다.

```json
{
  "type": "WS_DRAIN_NOTICE",
  "reason": "WS_SCALE_IN",
  "action": "RECONNECT_ON_CLOSE"
}
```

의미:

```text
- 현재 WS는 종료 예정
- 즉시 재연결하라는 뜻은 아님
- 기존 연결은 유지
- close가 발생하면 클라이언트가 fallback 재연결
```

---

### SERVER_DRAIN_NOTICE

GE가 DRAIN 진입 시 IN_GAME room에 보내는 안내다.

```json
{
  "type": "SERVER_DRAIN_NOTICE",
  "reason": "GE_SCALE_IN",
  "action": "LEAVE_AFTER_GAME"
}
```

의미:

```text
- 현재 GE가 종료 준비 중
- 진행 중인 게임은 가능한 한 마무리
- 게임 종료 후 방은 정리되고 로비로 이동
```

---

### ROOM_DRAIN_DELETE_SCHEDULED

GE가 DRAIN 진입 시 WAITING / READY / IDLE room에 보내는 안내다.

```json
{
  "type": "ROOM_DRAIN_DELETE_SCHEDULED",
  "reason": "GE_SCALE_IN",
  "action": "LEAVE_TO_LOBBY",
  "deleteAfterSeconds": 300
}
```

의미:

```text
- 게임 중이 아닌 방은 5분 후 삭제
- 새 게임 시작 금지
- 5분 후 로비 이동
```

---

### SERVER_DRAIN_FINAL

GE가 room을 실제로 정리할 때 보내는 이벤트다.

```json
{
  "type": "SERVER_DRAIN_FINAL",
  "reason": "GE_SCALE_IN",
  "action": "LEAVE_TO_LOBBY"
}
```

의미:

```text
- 이 방은 정리됨
- 클라이언트는 로비로 이동
```

---

### SERVER_DRAIN_FORCE_CLOSE

graceful period 만료가 가까워졌거나 초과했을 때 보내는 이벤트다.

```json
{
  "type": "SERVER_DRAIN_FORCE_CLOSE",
  "reason": "GRACEFUL_PERIOD_EXPIRED",
  "action": "FORCE_LEAVE"
}
```

의미:

```text
- 서버 종료 제한 시간 도달
- 남은 방/연결은 강제 정리
- 클라이언트는 로비 이동 또는 연결 종료 처리
```

---

## 12. 절대 놓치면 안 되는 구현 포인트

### GE

```text
1. SIGTERM 수신 즉시 DRAIN 전환
2. ge:{geId}=DRAIN 으로 갱신
3. DRAIN 중 heartbeat 유지
4. ge:load에서 자기 geId 제거
5. quick:available에서 자기 후보 즉시 제거
6. quick:ge:{geId}:rooms 보조 인덱스로 빠르게 제거
7. 새 private room 생성 차단
8. 새 quick room 생성 차단
9. quick join 차단
10. 기존 private roomCode 입장 허용
11. IN_GAME room에는 SERVER_DRAIN_NOTICE 발송
12. 게임 종료 로직에서 geStatus == DRAIN 재확인
13. WAITING/READY/IDLE room은 5분 후 삭제 스케줄 등록
14. DRAIN 상태 대기방에서는 새 게임 시작 차단
15. EMPTY room은 즉시 삭제
16. activeRoomCount == 0 이면 즉시 프로세스 정상 종료
17. graceful period 만료 시 남은 room 강제 종료
18. 종료 직전 Redis / RabbitMQ / roomCode 정리
```

### WS

```text
1. SIGTERM 수신 즉시 DRAIN 전환
2. readiness false 처리
3. 새 WebSocket handshake 차단
4. 기존 client에게 WS_DRAIN_NOTICE 발송
5. GE에 세션 상태를 주기적으로 묻지 않음
6. 재연결 시점 판단은 client에 위임
7. 기존 연결은 즉시 끊지 않음
8. 정상 종료 시 GE에 leave/disconnect 이벤트 전송
9. 비정상 종료에 대비해 GE의 stale session 정리 필요
```

### Client

```text
1. wsDrain / geDrain 상태값 분리
2. GE_DRAIN이 WS_DRAIN보다 우선
3. WS_DRAIN은 즉시 재연결 트리거가 아님
4. WS_DRAIN은 close 발생 시 fallback 재연결 참고값
5. GE_DRAIN은 방/게임 종료 명령으로 반드시 수용
6. GE_DRAIN 이후 wsDrain=true라고 해서 즉시 새 WS로 연결하지 않음
7. 기존 WS가 살아 있으면 그대로 사용
8. WS close가 발생하면 fallback 재연결
9. DRAIN 상태 대기방에서는 새 게임 시작 UI 비활성화
10. ROOM_DRAIN_DELETE_SCHEDULED 수신 시 삭제 예정 상태 표시 가능
```

### Lobby

```text
1. ACTIVE GE만 새 방 생성 후보로 사용
2. ACTIVE GE만 quick 후보로 사용
3. DRAIN GE는 quick join 후보에서 제외
4. private roomCode 입장은 DRAIN GE라도 허용 가능
5. ge:{geId} 없음이면 입장 실패 또는 종료 안내
6. DRAIN 상태로 입장하는 경우 client에 geStatus=DRAIN 전달 가능
```

---

## 13. 최종 요약

```text
GE scale-in
- 게임 서버 정리 정책
- 새 방 생성과 quick 매칭 차단
- 기존 private roomCode 입장은 허용
- 게임 중 방은 게임 종료 후 삭제
- 게임 중이 아닌 방은 5분 후 삭제
- 모든 room이 정리되면 즉시 종료

WS scale-in
- 연결 서버 정리 정책
- 새 WebSocket 연결 차단
- 기존 연결자에게 WS_DRAIN_NOTICE 발송
- 재연결 판단은 client에 위임
- WS는 GE에 세션 상태를 묻지 않음

Client
- GE_DRAIN이 WS_DRAIN보다 우선
- WS_DRAIN은 close 시 재연결 참고값
- GE_DRAIN은 방/게임 종료 명령
- wsDrain=true라고 즉시 새 WS로 연결하지 않음

핵심
- GE는 방과 게임을 정리한다.
- WS는 연결 종료를 안내한다.
- Client는 GE 명령을 우선하고, WS close 시 fallback 재연결한다.
```
