# 앱 정책 API와 캐시 설계

## 데이터/일관성 경계

DB 게시 revision ID가 공개 응답의 `id`이며 앱의 딥링크·공유·로컬 읽음 키다. DB가 유일한 권위 원천이다. 캐시는 직렬화 가능한 공통 DTO만 저장하고 사용자 상태나 JPA entity를 저장하지 않는다.

어드민 mutation과 epoch 증가가 한 DB 트랜잭션이므로 commit 전에 새 epoch가 보이거나 commit 후 이벤트가 유실되는 구간이 없다. poll은 at-least-once 방식이다. map clear는 멱등이고 성공 전에는 epoch를 갱신하지 않는다. 프로세스 시작 후 첫 poll도 cluster map을 clear한 뒤 현재 epoch를 기준선으로 잡아 새 member가 오래된 partition을 인계받는 경우를 막는다. 운영 배포 시 epoch row bootstrap을 먼저 보장한다.

poller가 성공적으로 적용한 epoch가 cache generation이다. 요청은 cache 접근 전에 generation을 한 번 캡처하고 상세 key를 `v4:e{epoch}:logical-key`로 고정한다. epoch 전환은 `generation bypass → distributed clear → 새 generation 활성화` 순서다. 따라서 이전 generation에서 시작한 DB load가 clear 이후 늦게 put하더라도 이전 generation key에만 남고 이후 요청은 새 generation만 조회한다. 최초 epoch 확인 전과 clear 실패 중에는 generation이 없으므로 get/put을 모두 건너뛴다.

홈 cache의 `validUntil = min(now + homeTTL, 다음 공개 시작/종료)`이고 상세는 `min(now + detailTTL, 해당 정책 공개 종료)`다. epoch는 내용/상태 변경을, boundary-aware expiry는 시간 흐름을 담당한다.

## 장애 모델

- Hazelcast read/write 실패: metric 증가, read는 miss 처리해 DB 조회.
- Hazelcast clear 실패: cache bypass를 켜서 다음 clear 성공 전까지 모든 read를 DB로 우회.
- epoch clear 실패: 새 epoch 미승인, 다음 1초 poll 재시도.
- epoch row 없음/query 실패: cache clear 안전 모드. DB 조회 API는 가능한 범위에서 계속 제공.
- member 분할: 양쪽 캐시는 비권위 사본이며 boundary/TTL 뒤 DB 재조회. 권위 락·상태 없음.
- DB 장애: 캐시 hit는 envelope 유효기간 내 제공 가능하지만 miss는 정상적으로 실패한다. DB 장애 중 stale 기간을 늘리지 않는다.

## cursor

cursor는 URL-safe Base64 JSON으로 sortVersion/asOf/마지막 source-group-date-input-id key를 담는다. JDBC 쿼리는 동일한 복합 `ORDER BY`, keyset `WHERE`, `LIMIT`을 DB에서 수행하고 최초 `asOf` 이후 입력은 제외한다. 애플리케이션은 최대 `limit+1`건만 받는다. 정책 수정으로 정렬키가 바뀌는 극단적 경우는 DB snapshot pagination이 아니므로 위치가 달라질 수 있다. 완전 snapshot이 필요해지면 revision snapshot ID를 cursor에 추가한다.

## 보안

`POLICY_NOT_AVAILABLE`은 PRIVATE, EXPIRED, REVIEW, PENDING_APPROVAL, REJECTED, CANCLED, DELETED 및 노출기간 밖을 모두 하나로 처리한다. 응답 message나 log에 내부 상태를 넣지 않는다. 목록 쿼리는 공개 조건을 SQL과 서비스에서 이중 확인한다.
