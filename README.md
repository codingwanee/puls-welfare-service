# HGI DCH CLOVR Welfare Serving Backend

임산부 복지 앱의 공개 정책 조회 전용 Spring Boot/Kotlin 서버다. 어드민 API를 호출하지 않고 동일 DB의 `SL_PBC_PREG_WMN_WEFR_SRV`를 read-only 계정으로 읽는다. 앱 설치 단위 읽음 상태는 앱 로컬 저장소의 책임이며 모든 공용 DTO에 `isRead`가 없다. 상세 성공 시 앱이 응답의 `id`를 읽음 처리하면 새 게시 버전의 새 ID는 자연스럽게 미읽음이 된다.

## API

servlet context path는 `/api/wsbd`, API base path는 `/v1`이다.
따라서 외부 요청 경로는 `/api/wsbd/v1`로 시작한다.

Swagger UI는 `http://localhost:8081/api/wsbd/swagger-ui/index.html`, OpenAPI JSON은
`http://localhost:8081/api/wsbd/v3/api-docs`에서 확인할 수 있다.

모든 serving controller 요청은 `TRACE_LOGGER`에 요청·응답·처리시간과 trace ID를 기록한다.
클라이언트가 `X-TRACE-ID` 헤더를 전달하면 해당 값을 사용하고, 없으면 trace ID를 빈 값(`[]`)으로 기록한다.
query parameter는 servlet에서 UTF-8 디코딩된 값을 사용해 한글 그대로 기록한다.
상세 body 로그는 일반 애플리케이션 logger의 DEBUG 레벨에서만 출력된다.

| API | 설명 |
|---|---|
| `GET /welfare-policies?sido=&sigungu=&cursor=&limit=20` | stable keyset cursor 목록, 최대 100건 |
| `GET /welfare-policies/{id}` | 정책 상세 |
| `GET /regions` | 법정동 원본 기반 전체 시도/시군구 카탈로그 |

`sido`가 없으면 서버 기본값(`DEFAULT_SIDO`, 기본 서울특별시)을 쓴다. 앱은 지역 미설정 시 서울을 명시적으로 요청하는 것을 권장한다. `sigungu`가 있으면 카탈로그에 등록된 시도/시군구 조합이어야 한다. `sigungu`를 생략하면 전국 정책 + 해당 시도 공통 정책 + 해당 시도 산하 모든 시군구 정책을 조회한다. `sigungu`를 지정하면 전국 정책 + 해당 시도 공통 정책 + 지정한 시군구 정책만 조회한다.

공개 조건은 `PUBLISHED`이며 `EXPO_STR_DTHMS <= now < EXPO_ND_DTHMS`다(각 경계가 NULL이면 제한 없음). 실제 ID가 없으면 `404/POLICY_NOT_FOUND`, 행은 있지만 공개 조건에 맞지 않으면 내부 상태를 노출하지 않는 `410/POLICY_NOT_AVAILABLE`이다.

목록은 설정 버전 `POLICY_SORT_VERSION` 아래 `INTERNAL 우선 → RQ_PERD_ORDR_GP_ORDR null-last → RQ_PERD_ORDR_DT null-last → INP_DTHMS DESC → ID DESC`다. 정렬과 cursor 이후 조건, `LIMIT`을 DB SQL에 적용하므로 페이지마다 visible 전체 행을 메모리에 적재하지 않는다. 서비스의 정렬은 DB가 반환한 최대 `limit+1`건에 대한 방어적 검증이다. 정렬 변경 시 version도 올리면 기존 cursor를 명시적으로 거절한다. cursor에는 최초 페이지의 `asOf`와 마지막 sort key가 compact 배열로 들어가 새 입력에 의한 중복을 막는다. 새 응답은 compact cursor를 발급하지만 기존 JSON object cursor도 decode한다.

목록과 상세 응답은 어드민의 공개 정책 필드명을 사용한다. 목록은 `id`, `serviceName`, `servicePurposeSummary`, `competentAgency`만 제공한다. 상세는 화면에 사용하는 정책 내용과 `onlineApplicationUrl`, `siteUrl`을 평면으로 제공하며, 선택된 원천값이 없더라도 해당 필드를 `null`로 포함한다. 지원혜택은 `SUPT_BNFT_SUMM_CN`을 `supportBenefitSummary`로 그대로 내려주고 금액을 재계산하지 않는다. `serviceId`, `dataType`, 지역 원본·조합 필드, `landingUrl`, `shareUrl`은 공개 응답에서 제외한다.

## 실행과 2-node cluster

```bash
DB_URL='jdbc:mysql://db:3306/welfare' DB_USERNAME=puls_app_ro DB_PASSWORD=... \
HAZELCAST_CLUSTER_NAME=welfare-prod HAZELCAST_MEMBERS=10.0.1.10:5701,10.0.1.11:5701 \
HAZELCAST_PORT=5701 SERVER_PORT=8081 ./gradlew bootRun
```

홈 화면도 별도 API 없이 `GET /welfare-policies?limit=10`의 `items`를 사용한다.

두 번째 프로세스도 같은 cluster-name/member 목록으로 실행한다. multicast와 auto-detection은 꺼져 있고 TCP/IP seed를 사용한다. Kubernetes/Eureka 등 discovery가 필요한 환경에서는 `HazelcastConfig`의 join discovery를 배포 프로필로 교체하되 cluster-name은 환경별로 분리한다. map은 상세 조회용 `app-policy-detail-v4` 하나이며, key는 `v4:e{epoch}:` prefix이고 backup-count=1, PER_NODE bounded LRU, map TTL이 적용된다. 목록 응답·엔티티·회원별 응답·읽음 상태·락은 저장하지 않는다.

상세 캐시 envelope는 DB 노출 종료와 설정 TTL 중 빠른 경계까지만 유효하다. Hazelcast 예외는 miss로 취급하고 DB로 우회한다. epoch poll의 일시 장애에는 마지막으로 확인한 generation을 최대 5초 유지하고, 그 이후에는 cache generation을 비활성화해 DB로 우회한다. split brain에서도 DB가 권위 데이터다.

## 무효화와 운영

어드민의 모든 내용/가시성 변경 트랜잭션은 같은 트랜잭션에서 다음을 실행한다.

```sql
UPDATE SL_PBC_POLICY_CACHE_EPOCH
   SET EPOCH = EPOCH + 1, MDF_DTHMS = CURRENT_TIMESTAMP(6)
 WHERE CACHE_NAME = 'APP_POLICY_V1';
```

영향 행이 0이면 어드민 트랜잭션을 rollback한다. 앱은 기본 1초마다 읽고 현재 generation을 새 epoch로 원자적으로 전환한다. versioned map 전체를 clear하지 않으며 이전 generation 데이터는 map TTL/LRU로 제거된다. 요청은 시작 시 generation을 캡처하므로 전환 뒤 완료된 과거 DB load도 과거 `e{epoch}` key에만 put되고 새 generation 요청에는 보이지 않는다. 동일 app instance에서 같은 generation key의 cache miss가 동시에 발생하면 한 요청만 DB를 조회하고 나머지는 그 결과를 공유한다.

DDL의 단일 기준은 어드민 레포의 `docs/db/migration/V20260730_01__create_policy_cache_epoch.sql`이다. 적용 순서는 **어드민 canonical epoch DDL → 선택적 [앱 조회 인덱스](db/migration/manual/V20260730_02__create_app_policy_read_indexes.sql) → 앱 서버 → 어드민 서버**다. 앱 레포는 epoch 테이블을 중복 정의하지 않는다.

앱 계정은 정책/epoch 테이블 `SELECT`만 부여한다. 운영 기본은 `spring.jpa.hibernate.ddl-auto=validate`, Hikari `read-only=true`다. JPA immutable schema entity가 핵심 가시성 컬럼을 검증하고 `SchemaProbe`가 실제 JDBC 조회 전체 컬럼과 epoch 컬럼을 시작 시 검증한다. 두 레포 어느 쪽도 운영에서 `ddl-auto=update`를 사용하지 않는다.

`POLICY_CACHE_ENABLED=false`이면 Hazelcast, epoch poller와 epoch schema probe가 모두 비활성화되어 epoch 테이블 없이 DB 직접 조회 모드로 기동할 수 있다. 정책 read model 테이블 검증은 캐시 설정과 관계없이 항상 수행한다.

관측 metric: `policy.cache.requests{map,result=hit|miss}`, `policy.cache.fallback`, `policy.cache.hazelcast.member.count`, `policy.cache.generation`, `policy.cache.generation.transitions`, `policy.cache.invalidation.lag.millis`, `policy.cache.epoch.poll.failures`, `policy.cache.epoch.row.missing`.

## 검증

```bash
./gradlew test
```

정렬 tie-break, 전국/시도/시군구 포함과 가시성, 404/410 비노출 오류, 원천 필드와 버튼 URL 매핑, 전체 지역 카탈로그, 두 embedded member 공유, epoch generation 전환·row-missing 안전 모드, 동일 키 miss 병합, Hazelcast 종료 시 DB fallback을 자동 테스트한다.
