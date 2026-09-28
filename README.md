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

`sido`와 `sigungu`가 모두 없으면 지역 필터 없이 전체 정책을 조회한다. `sigungu`가 있으면 `sido`도 함께 입력해야 하며 카탈로그에 등록된 시도/시군구 조합이어야 한다. `sido`만 입력하면 전국 정책 + 해당 시도 공통 정책 + 해당 시도 산하 모든 시군구 정책을 조회한다. `sigungu`까지 지정하면 전국 정책 + 해당 시도 공통 정책 + 지정한 시군구 정책만 조회한다.

공개 조건은 `PUBLISHED`이며 `EXPO_STR_DTHMS <= now < EXPO_ND_DTHMS`다(각 경계가 NULL이면 제한 없음). 실제 ID가 없으면 `404/POLICY_NOT_FOUND`, 행은 있지만 공개 조건에 맞지 않으면 내부 상태를 노출하지 않는 `410/POLICY_NOT_AVAILABLE`이다.

목록은 설정 버전 `POLICY_SORT_VERSION` 아래 `INTERNAL 우선 → RQ_PERD_ORDR_GP_ORDR null-last → RQ_PERD_ORDR_DT null-last → INP_DTHMS DESC → ID DESC`다. 정렬과 cursor 이후 조건, `LIMIT`을 DB SQL에 적용하므로 페이지마다 visible 전체 행을 메모리에 적재하지 않는다. 서비스의 정렬은 DB가 반환한 최대 `limit+1`건에 대한 방어적 검증이다. 정렬 변경 시 version도 올리면 기존 cursor를 명시적으로 거절한다. cursor에는 최초 페이지의 `asOf`와 마지막 sort key가 compact 배열로 들어가 새 입력에 의한 중복을 막는다. 새 응답은 compact cursor를 발급하지만 기존 JSON object cursor도 decode한다.

목록과 상세 응답은 어드민의 공개 정책 필드명을 사용한다. 목록은 `id`, `serviceName`, `servicePurposeSummary`, `competentAgency`, `sido`, `sigungu`를 제공한다. 상세는 화면에 사용하는 정책 내용과 `onlineApplicationUrl`을 평면으로 제공하며, 선택된 원천값이 없더라도 해당 필드를 `null`로 포함한다. 지원혜택은 `SUPT_BNFT_SUMM_CN`을 `supportBenefitSummary`로 그대로 내려주고 금액을 재계산하지 않는다. `serviceId`, `dataType`, `siteUrl`, `landingUrl`, `shareUrl`은 공개 응답에서 제외한다.

## 실행

DB 비밀번호는 Jasypt `ENC(...)` 형식으로 암호화한 뒤 배포 환경의 마스킹된 변수로 주입한다.
복호화 마스터 키는 서비스 전용 `KMS_PASSWORD`로 별도 주입하며 저장소, 배포 YAML, 로그에 남기지 않는다.
암호화 규격은 `PBEWithHMACSHA512AndAES_256`, 100,000 iterations, random IV, Base64다.
키 또는 암호문이 없거나 올바르지 않으면 애플리케이션은 기동하지 않는다.

```bash
KMS_PASSWORD='<serving-master-key>' DB_PASSWORD='ENC(<encrypted-db-password>)' \
DB_URL='jdbc:mysql://db:3306/welfare' DB_USERNAME=puls_app_ro \
SERVER_PORT=8081 ./gradlew bootRun
```

`KMS_PASSWORD`와 DB 비밀번호 원문은 서로 다른 값이어야 한다. admin 서비스와도 마스터 키를
공유하지 않는다. 이미 저장소 이력에 포함된 기존 DB 자격증명은 배포 전에 교체해야 한다.

새 비밀값의 `ENC(...)` 값은 저장소에 기록하지 않고 다음 로컬 명령으로 생성한다. zsh의
숨김 입력을 사용하므로 키와 평문이 shell history나 명령행 인자에 남지 않는다.

```bash
read -s 'KMS_PASSWORD?Master key: '; export KMS_PASSWORD; echo
read -s 'SECRET_TO_ENCRYPT?Secret to encrypt: '; export SECRET_TO_ENCRYPT; echo
./gradlew encryptSecret --quiet --no-daemon
unset KMS_PASSWORD SECRET_TO_ENCRYPT
```

출력된 한 줄 전체를 `DB_PASSWORD`의 마스킹된 배포 변수로 등록한다.

홈 화면도 별도 API 없이 `GET /welfare-policies?limit=10`의 `items`를 사용한다.

목록과 상세 조회는 항상 정책 read model DB를 직접 조회한다. 앱 계정은 정책 테이블 `SELECT`만 부여한다.
운영 기본은 `spring.jpa.hibernate.ddl-auto=validate`, Hikari `read-only=true`다. JPA immutable schema entity가
핵심 가시성 컬럼을 검증하고 `SchemaProbe`가 실제 JDBC 조회 전체 컬럼을 시작 시 검증한다.

관측 metric은 `welfare.serving.*` namespace를 정식 계약으로 사용한다. 정책 목록/상세와 repository 지연,
목록 반환 건수를 Prometheus로 노출하며 모든 시계열에는 `application=wsbd`가 포함된다.

## 검증

가입자 10만 명 기준 DAU·목표 RPS 산정과 k6 부하 테스트 실행 방법은
[Serving API 성능 테스트](docs/serving-performance-test.md)를 참고한다.

```bash
./gradlew test
```

정렬 tie-break, 전국/시도/시군구 포함과 가시성, 404/410 비노출 오류, 원천 필드와 버튼 URL 매핑,
전체 지역 카탈로그와 DB 직접 조회를 자동 테스트한다.

## 지역 카탈로그 무재기동 갱신

외부 파일 교체 후 인스턴스별 reload API를 호출하는 [운영 절차](docs/region-catalog-reload.md)를 참고하세요. 기존 공개 지역·정책 조회 API 계약은 유지됩니다.
