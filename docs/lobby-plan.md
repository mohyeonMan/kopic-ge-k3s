# Lobby 라우팅/클러스터링 구현 계획

## 1. 목표
- Lobby는 사용자가 접속할 GE(Game Engine)를 안내한다.
- Lobby는 room 생성/room 선택의 최종 소유자가 아니다.
- Client는 Lobby에서 받은 routing token 하나만 WS 연결에 사용한다.
- WS는 token을 로컬에서 검증해 `geId`와 `action`을 얻고 해당 GE로 첫 요청을 전달한다.
- GE는 기존 room actor와 로컬 room store를 기준으로 room 선택/생성/참가/검증을 수행한다.
- Redis에는 라우팅에 필요한 최소 key만 저장한다.
- Redis directory는 source of truth가 아니라 GE 로컬 room state에서 재생성 가능한 파생 인덱스로 본다.

```text
Client
  -> Lobby: route 요청
  <- Lobby: routeToken
Client
  -> WS: wss://.../ws?routeToken={routeToken}
WS
  -> WS: routeToken 로컬 검증
WS
  -> GE: geId 기준으로 첫 요청 전달
GE
  -> 로컬 room actor 처리
GE
  -> WS: 처리 결과 전달
WS
  -> Client
```

## 2. 역할 경계

### 2.1 Lobby
- Redis directory를 조회해 가장 적절한 `geId`를 고른다.
- local memory에 GE/room 상태를 저장하지 않는다.
- quick join 요청이면:
  - join 가능한 quick room이 있는 GE를 우선 안내한다.
  - 후보가 없으면 `ge:load`가 낮은 ACTIVE GE를 안내한다.
- private room 생성 요청이면:
  - `ge:load`가 낮은 ACTIVE GE를 안내한다.
- private roomCode 입장 요청이면:
  - `roomcode:{roomCode}`로 GE를 찾고 해당 GE를 안내한다.
- Client에는 routing token 하나만 반환한다.

### 2.2 WS
- `routeToken`을 로컬에서 검증한다.
- token에서 `geId`, `action`, `nickname`, 선택적 `roomCode`를 얻는다.
- Redis 조회, GE 상태 확인, room 상태 확인은 하지 않는다.
- token 만료 또는 서명 검증 실패만 연결 단계에서 거절한다.
- 이후 클라이언트 이벤트는 해당 GE로 전달한다.

### 2.3 GE
- pod마다 고유한 `geId`를 가진다.
  - 현재 로컬 기본값은 `ge-local`이고, Kubernetes에서는 추후 pod name을 `KOPIC_NODE_ID`로 주입한다.
- quick join 요청은 GE 로컬 quick room 후보에서 처리한다.
  - 로컬 join 가능 quick room이 있으면 그 room에 참가시킨다.
  - 없으면 새 quick room을 생성하고 참가시킨다.
- private room 생성은 GE가 수행한다.
  - roomId/roomCode 생성
  - Redis `roomcode:{roomCode}` 전역 중복 확보
- private roomCode join은 GE 로컬 roomCode index로 최종 검증한다.
- 정원 초과, 중복 session, host 권한 등은 기존 room actor 로직에서 최종 검증한다.
- room 상태 변화에 따라 Redis directory를 갱신한다.
- Redis 복구 시 private roomCode는 로컬 room state로 재등록한다.
- quick room 후보 전체 재등록은 필수 정확성 요건이 아니라 라우팅 품질 개선 옵션으로 둔다.

## 3. Redis 최소 데이터 모델

MVP에서는 아래 4종류만 저장한다.

```text
ge:{geId}
ge:load
quick:available
roomcode:{roomCode}
```

`room:{roomId}` hash, reservation key, room metadata field는 저장하지 않는다.

### 3.1 `ge:{geId}`
GE 생존 여부와 신규 배정 가능 여부만 저장한다.

```text
key:   ge:kopic-ge-0
type:  String
value: ACTIVE | DRAINING
ttl:   10~15s
```

- key가 없으면 dead GE로 간주한다.
- `ACTIVE`이면 신규 route 후보가 될 수 있다.
- `DRAINING`이면 신규 quick/private create 후보에서 제외한다.
- 진행 중인 기존 room은 GE 로컬 상태로 계속 처리한다.

### 3.2 `ge:load`
새 room 생성이 필요할 때 어떤 GE가 덜 바쁜지만 판단한다.

```text
key:    ge:load
type:   ZSET
member: geId
score:  loadScore
```

초기 계산식:

```text
loadScore = participants + rooms * roomWeight
```

- `loadScore`는 GE가 짧은 load interval마다 계산해서 기록한다.
- Redis에는 계산 결과만 저장한다.
- Lobby는 score가 낮은 ACTIVE GE를 신규 room 생성 후보로 선택한다.
- 기본 load interval은 1분으로 두고, reconciliation 주기와 분리한다.

### 3.3 `quick:available`
join 가능한 quick room 후보 목록이다.

```text
key:    quick:available
type:   ZSET
member: {geId}:{roomId}
score:  availableAtMillis
```

예:

```text
ZADD quick:available 1769270000000 kopic-ge-0:rid_a
ZADD quick:available 1769270005000 kopic-ge-1:rid_b
```

- score가 낮을수록 먼저 candidate가 된 quick room이다.
- Lobby는 `ZRANGE quick:available 0 {scanLimit - 1}`로 앞쪽 후보를 짧게 훑는다.
- Lobby는 member에서 `geId`를 파싱해 해당 GE를 안내한다.
- 해당 GE가 dead/DRAINING이면 다음 후보를 본다.
- Lobby는 stale member를 삭제하지 않는다. `quick:available`의 등록/삭제는 GE 책임이다.
- 후보를 `scanLimit`개까지 확인해도 ACTIVE GE가 없으면 `ge:load` fallback으로 넘어간다.
- GE는 로컬 quick candidate add/remove와 동일한 의미로 Redis 후보를 갱신한다.

GE 갱신 규칙:

```text
quick room 생성:
  ZADD quick:available {availableAtMillis} {geId}:{roomId}

quick room full -> not full:
  ZADD quick:available {availableAtMillis} {geId}:{roomId}

quick room이 계속 join 가능 상태인 참가/퇴장:
  Redis 갱신 없음. 로컬 candidate도 계속 유지된다.

quick room full 또는 종료:
  ZREM quick:available {geId}:{roomId}

GE DRAINING:
  해당 geId prefix의 후보 제거
```

### 3.4 `roomcode:{roomCode}`
private roomCode로 어느 GE에 보내야 하는지만 저장한다.

```text
key:   roomcode:ABCDEF
type:  String
value: geId
ttl:   room lifetime + safety margin
```

예:

```text
SET roomcode:ABCDEF kopic-ge-0 NX EX 3600
```

- GE가 private room 생성 시 Redis `SET NX`로 전역 roomCode 중복을 방지한다.
- Redis `SET NX` 실패 또는 Redis 장애 시 현재 roomCode 생성을 실패로 보고 roomCode를 새로 생성해 재시도한다.
- 재시도 횟수를 초과하면 private room 생성은 fail closed로 거절한다.
- Lobby는 `roomcode:{roomCode}`를 조회해 해당 GE를 안내한다.
- GE는 실제 join 시 로컬 roomCode index로 room 존재/정원/권한을 검증한다.
- private room 종료 시 GE가 삭제한다.
- Redis 복구 시 GE가 로컬 private room을 순회해 재등록한다.

### 3.5 MVP에서 저장하지 않는 것
아래 key/field는 v1 Redis 모델에 두지 않는다.

```text
room:{roomId}
reservation:{id}
quick:{geId}
roomId -> geId 별도 key
roomCode -> roomId 별도 key
participantCount hash field
capacity hash field
joinable hash field
```

- GE가 로컬 room actor와 로컬 room store로 최종 검증한다.
- token 1회성 소비나 과예약 완화가 필요해지면 별도 reservation key를 v2 옵션으로 추가한다.

## 4. Lobby API

MVP의 route API는 세 종류로 둔다. 모든 응답은 `routeToken` 하나만 반환한다.

```http
POST /routes/quick
Content-Type: application/json

{ "nickname": "pobi" }
```

```http
POST /routes/private
Content-Type: application/json

{ "nickname": "pobi" }
```

```http
POST /routes/private/join
Content-Type: application/json

{ "nickname": "pobi", "roomCode": "ABCDEF" }
```

성공 응답:

```json
{
  "routeToken": "..."
}
```

- quick join과 private create 요청에는 `nickname`만 필요하다.
- private join 요청에는 `nickname`, `roomCode`가 필요하다.
- Lobby는 routeToken 생성 전 Redis directory에서 연결할 `geId`를 결정한다.
- `action`은 client가 기존 WS 연결 시도에 사용하던 action 값을 route 종류에 맞게 token에 그대로 넣는다.
- Redis 장애, ACTIVE GE 없음, roomCode 없음 등 route 발급이 불가능한 경우에는 routeToken을 반환하지 않는다.

## 5. Routing Token
- Lobby가 client에 반환하는 값은 `routeToken` 하나뿐이다.
- client는 token을 해석하지 않고 WS 연결 파라미터로 그대로 전달한다.
- WS는 Lobby와 같은 secret으로 token을 로컬에서 검증한다.
- WS는 token 복원 후 Redis를 조회하지 않는다.
- token payload는 routing에 필요한 최소 정보만 포함한다.
- routeToken은 JWS JWT를 사용한다. 암호화하지 않으며, payload는 client가 읽을 수 있다고 가정한다.

Quick join route:

```json
{
  "action": "<existing-quick-join-action>",
  "nickname": "pobi",
  "geId": "kopic-ge-0",
  "exp": 1710000010000
}
```

Private create route:

```json
{
  "action": "<existing-private-create-action>",
  "nickname": "pobi",
  "geId": "kopic-ge-1",
  "exp": 1710000010000
}
```

Private join route:

```json
{
  "action": "<existing-private-join-action>",
  "nickname": "pobi",
  "roomCode": "ABCDEF",
  "geId": "kopic-ge-0",
  "exp": 1710000010000
}
```

- `action`은 기존 client/WS 연결 정책에서 쓰던 값을 그대로 사용한다.
- `roomCode`는 private join route에만 넣는다.
- token은 client가 변조할 수 없게 서명한다.
- 서명 secret은 외부에서 주입한다.
  - 예: `KOPIC_ROUTE_TOKEN_SECRET`
- token TTL은 짧게 둔다. 초기값은 10초를 권장한다.

## 6. 주요 흐름

### 6.1 Quick Join
```text
1. Client -> Lobby: quick route 요청
2. Lobby:
   - quick:available에서 후보 room 조회
   - 후보 member들을 scanLimit개까지 앞에서부터 확인하고 ge:{geId}=ACTIVE인 후보를 선택
   - 후보가 없으면 ge:load에서 낮은 ACTIVE GE 선택
   - routeToken 생성: action, nickname, geId, exp 포함
3. Client -> WS: routeToken 포함해 연결
4. WS:
   - routeToken 로컬 검증
   - token의 action/nickname/geId로 해당 GE에 첫 요청 전달
5. GE:
   - 로컬 join 가능 quick room 조회
   - 있으면 해당 room에 join
   - 없으면 새 quick room 생성 후 join
   - quick:available 갱신
   - join 성공 시 client에 304 응답
```

- 방이 0개인 순간 동시 요청이 들어오면 quick room이 2개 생길 수 있다.
- MVP에서는 이를 허용한다.
- 이후 room 단위 `quick:available`과 `close-if-empty`로 자연스럽게 수렴시킨다.
- 실제 문제가 되면 v2에서 짧은 TTL의 `quick:create-lock`을 추가한다.

### 6.2 Private Room 생성
```text
1. Client -> Lobby: private create route 요청
2. Lobby:
   - ge:load에서 낮은 ACTIVE GE 선택
   - routeToken 생성: action, nickname, geId, exp 포함
3. Client -> WS: routeToken 포함해 연결
4. WS:
   - routeToken 로컬 검증
   - token의 action/nickname/geId로 해당 GE에 첫 요청 전달
5. GE:
   - private room 생성
   - roomCode 생성
   - SET roomcode:{roomCode} {geId} NX EX ... 로 전역 중복 확보
   - 실패하면 roomCode 재생성
   - host join
   - join 성공 시 client에 304 응답
```

### 6.3 Private RoomCode 입장
```text
1. Client -> Lobby: roomCode route 요청
2. Lobby:
   - GET roomcode:{roomCode} -> geId
   - ge:{geId}=ACTIVE 확인
   - routeToken 생성: action, nickname, roomCode, geId, exp 포함
3. Client -> WS: routeToken 포함해 연결
4. WS:
   - routeToken 로컬 검증
   - token의 action/nickname/roomCode/geId로 해당 GE에 첫 요청 전달
5. GE:
   - roomCode로 로컬 room 조회
   - room 존재/정원/중복 session 검증
   - join 처리
   - join 성공 시 client에 304 응답
```

- `roomcode:{roomCode}`가 없으면 Lobby가 방 없음으로 응답한다.
- token이 stale이면 GE가 로컬 검증에서 거절하고 client가 Lobby route부터 재시도한다.

### 6.4 Client Retry
- client는 Lobby route 발급과 WS 연결/초기 join 응답 수신까지를 하나의 시도로 본다.
- WS 연결 요청 후 3초 안에 GE join 성공 응답인 `304`를 받지 못하면 해당 시도는 실패로 간주한다.
- 실패하면 기존 routeToken을 재사용하지 않고 Lobby route 요청부터 다시 시작한다.
- 재시도는 1초 간격으로 최대 3회 수행한다.
- 3회 모두 실패하면 마지막 실패 사유를 사용자에게 표시한다.

## 7. 동시성 정책

### 7.1 Quick room 동시 생성
- 방이 없는 상태에서 동시 quick route가 들어오면 여러 GE 또는 같은 GE에서 quick room이 2개 이상 생길 수 있다.
- MVP에서는 정합성 문제가 아니라 일시적인 매칭 품질 저하로 본다.
- GE room actor가 정원 초과와 중복 session을 최종적으로 막는다.
- 빈 방은 기존 `close-if-empty` 타이머로 정리한다.
- 다음 사용자들은 `quick:available` 기준으로 먼저 available 상태가 된 room의 GE로 안내되어 수렴한다.

### 7.2 Stale routing
- Redis 후보가 stale일 수 있다.
- WS는 Redis를 보지 않으므로 stale 여부를 판단하지 않는다.
- GE가 로컬 room state로 최종 판단한다.
- 실패한 client는 Lobby route부터 2~3회 재시도한다.

### 7.3 V2 옵션
동시 생성이 실제 운영 문제가 되면 아래를 추가한다.

```text
quick:create-lock
  type: String
  value: geId 또는 random
  ttl: 1~2s
```

- quick 후보가 없을 때 Lobby가 `SET quick:create-lock ... NX EX 2`를 시도한다.
- lock 획득 요청만 낮은 load GE로 안내한다.
- lock 실패 요청은 100~200ms 후 `quick:available`을 재조회하거나 retry 응답을 반환한다.

## 8. Redis 장애/복구 정책

### 8.1 Redis 장애 중
- 기존 room 내부 이벤트는 Redis 없이 계속 처리한다.
  - draw/chat/leave/start/game timer 등은 GE 로컬 room actor가 처리한다.
- Lobby 신규 route 발급은 degraded 응답으로 거절한다.
  - quick route
  - private create route
  - roomCode route
- Redis 장애 전에 이미 발급된 token은 WS가 로컬 검증 후 GE로 전달할 수 있다.
- GE는 로컬 검증으로 기존 요청을 처리할 수 있다.
- private room 생성은 Redis 장애 중 fail closed로 처리한다.
  - roomCode 전역 유니크 보장이 Redis `SET NX`에 의존하기 때문이다.

### 8.2 Redis HA
- 초기 목표는 Redis Cluster sharding보다 Redis 고가용성이다.
- 권장 구성은 primary-replica + Sentinel 또는 managed Redis HA다.

```text
Redis Primary
Redis Replica 1
Redis Replica 2
Sentinel 1
Sentinel 2
Sentinel 3
```

- 애플리케이션은 특정 Redis pod 주소가 아니라 Sentinel master name 또는 managed endpoint로 접속한다.
- primary 장애 시 Sentinel/managed Redis가 replica를 승격하고, GE/Lobby Redis client는 새 primary로 재연결한다.
- Redis Cluster sharding은 초기 단계에서 우선순위를 낮춘다.

### 8.3 GE Reconciliation
현재 GE는 Redis 복구를 별도 reconnect hook으로 감지하지 않고, 주기 작업과 이벤트 기반 갱신으로 Redis directory를 보정한다.

```text
1. ge:{geId} heartbeat 주기 갱신
2. ge:load score 주기 재계산
3. 로컬 RoomSessionStore 순회
4. private room이면 roomcode:{roomCode} -> geId 주기 refresh
5. quick:available은 이벤트 기반 add/remove와 stale cleanup으로 보정
```

- Redis 갱신은 두 레이어로 수행한다.
  - event-driven update: `roomcode:{roomCode}`, `quick:available`만 필요한 순간 즉시 갱신
  - load refresh: 기본 1분마다 로컬 room 상태 기준으로 `ge:load` 갱신
  - periodic reconciliation: 기본 1시간마다 로컬 private roomCode를 refresh하고 GE load counter를 보정
- Redis 전체 유실 후에도 신규 quick route는 `ge:load` fallback으로 GE에 도달할 수 있고, GE 로컬 quick candidate가 최종 room 선택을 수행한다.
- `quick:available` 전체 재등록은 로비 라우팅 품질과 복구 시간을 줄이기 위한 v2 옵션으로 둔다.

## 9. Scale-in MVP
- scale-in migration은 MVP에서 제외한다.
- `DRAINING` GE는 신규 quick/private create 후보에서 제외한다.
- DRAINING 시 `quick:available`에서 해당 GE의 room 후보를 제거한다.
- DRAINING 시 `ge:load`에서도 해당 GE를 제거한다.
- 진행 중인 room/game은 가능한 한 끝까지 유지한다.
- 게임 종료 후 client에게 이 GE가 종료 예정이며 로비 재입장이 필요하다는 이벤트를 보낸다.
- client는 Lobby route를 다시 받아 새 GE로 접속한다.
- `DRAINING` 전환 API는 별도 운영 API로 추가할 예정이다.

## 10. 구현 포인트

### 10.1 GE 저장소
- Redis client/config 추가
- `GeStateRecorder`
  - `ge:{geId}` heartbeat
  - load interval마다 `ge:load` score 갱신
  - 종료 시 `DRAINING` 마킹 및 `ge:load` 제거
- `DefaultPrivateRoomCodeStore`
  - `roomcode:{roomCode}` 등록/삭제
  - 주기 reconciliation 시 private roomCode refresh
- `DefaultQuickRoomCandidateStore`
  - `quick:available` 등록/삭제
  - 주기 reconciliation 시 stale quick 후보 제거
- private roomCode 생성 로직 변경
  - roomCode 전역 중복 판단은 Redis `SET NX` 결과를 기준으로 한다.
  - `SET NX` 실패 또는 Redis 장애 시 room 생성 실패로 전파하고 새 roomCode로 재시도한다.
- room lifecycle hook
  - private room 생성/종료 시 `roomcode:{roomCode}` 갱신
  - quick room 생성/종료/join/leave/full 전환 시 `quick:available` 갱신
- Redis reconnect hook은 MVP 필수 범위에서 제외한다.
- Kubernetes에서는 추후 pod name을 `KOPIC_NODE_ID`로 주입한다.

### 10.2 Lobby 저장소
- quick route
  - `quick:available` 후보가 있으면 member의 GE 안내
  - 후보가 없으면 `ge:load` 낮은 ACTIVE GE 안내
- private create route
  - `ge:load` 낮은 ACTIVE GE 안내
- roomCode route
  - `roomcode:{roomCode}` 조회 후 GE 안내
- `routeToken` 발급

### 10.3 WS 저장소
- `routeToken` 로컬 검증
- `geId`를 connection/session routing context로 저장
- Redis 조회는 추가하지 않음

## 11. 운영 설정

routeToken 설정은 Lobby와 WS가 같은 값을 사용한다.

```yaml
kopic:
  route-token:
    secret: ${KOPIC_ROUTE_TOKEN_SECRET}
    ttl: ${KOPIC_ROUTE_TOKEN_TTL:10s}
```

Redis key 설정은 Lobby와 GE가 같은 값을 사용한다.

```yaml
kopic:
  redis:
    keys:
      ge-prefix: ${KOPIC_REDIS_KEY_GE_PREFIX:ge:}
      ge-load: ${KOPIC_REDIS_KEY_GE_LOAD:ge:load}
      quick-available: ${KOPIC_REDIS_KEY_QUICK_AVAILABLE:quick:available}
      room-code-prefix: ${KOPIC_REDIS_KEY_ROOM_CODE_PREFIX:roomcode:}
```

Lobby route 선택 설정:

```yaml
kopic:
  lobby:
    quick-candidate-scan-limit: ${KOPIC_LOBBY_QUICK_CANDIDATE_SCAN_LIMIT:5}
```

- `kopic.route-token.secret`은 외부 secret으로 주입한다.
- `kopic.route-token.ttl` 기본값은 10초다.
- `kopic.redis.keys.*`는 GE와 Lobby가 반드시 같은 값을 사용해야 한다.
- `quick-candidate-scan-limit`는 `quick:available` 앞쪽 후보를 몇 개까지 확인할지 정한다.

## 12. 검증 계획
- `./gradlew compileJava`
- quick room이 없을 때 GE가 새 quick room을 생성하는지 확인
- quick room이 있을 때 Lobby가 `quick:available`의 GE로 안내하는지 확인
- private room 생성 시 `roomcode:{roomCode}`가 Redis에 등록되는지 확인
- private room 종료 시 `roomcode:{roomCode}`가 삭제되는지 확인
- GE crash 후 `ge:{geId}` TTL 만료로 Lobby 신규 route 후보에서 제외되는지 확인
- Redis failover 후 `ge:*`, `ge:load`, `roomcode:*`가 주기 작업으로 복구되는지 확인
- Redis 장애 중 기존 room 내부 이벤트가 계속 처리되는지 확인
- Redis 장애 중 Lobby 신규 route가 degraded 응답을 반환하는지 확인

## 13. 완료 기준
- Redis key 종류가 `ge:{geId}`, `ge:load`, `quick:available`, `roomcode:{roomCode}`로 제한된다.
- Lobby pod를 여러 개 띄워도 local state 없이 동일하게 동작한다.
- quick route는 join 가능한 quick room이 있는 GE를 우선 안내한다.
- quick 후보가 없으면 load가 낮은 ACTIVE GE를 안내한다.
- private roomCode만으로 해당 GE를 찾을 수 있다.
- WS는 Redis를 조회하지 않는다.
- GE가 room 선택/생성/참가/검증의 최종 소유자다.
- Redis HA failover 후 GE가 주기 작업으로 heartbeat/load/private roomCode를 복구한다.
