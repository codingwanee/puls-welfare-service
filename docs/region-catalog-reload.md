# 지역 카탈로그 reload 운영

외부 JSON 파일을 교체한 뒤 각 인스턴스에 reload를 호출하면 서버 재기동 없이 지역 목록·검증 기준을 변경한다. 기존 공개 API의 URL과 요청·응답 구조는 유지된다.

## 시도·시군구 JSON 생성

**생성 도구는 wabd 저장소(`hgi-dch-clovr-welfare-admin-backend`)에 있다.** 아래 명령은 모두 wabd 저장소 루트에서 실행한다. wsbd 저장소에서 문서를 읽는 경우에도 wabd 저장소로 이동해서 생성한다. 생성 결과는 wabd·wsbd가 공통으로 사용하는 동일 형식의 JSON이므로 한 번만 생성해 같은 파일을 양쪽에 배포한다.

### KIKmix 엑셀 파일로 생성

시행일자에 맞는 KIKmix xlsx 원본과 **현재 운영 중인 카탈로그 JSON 사본**을 준비한다. 경로의 `YYYYMMDD`는 원본 기준일로 바꾼다.

```bash
# wabd 저장소 루트에서 실행
./gradlew toolsJar

java -jar build/libs/region-catalog-cli-*.jar convert \
  --kikmix '/path/to/KIKmix.YYYYMMDD.xlsx' \
  --baseline '/path/to/current-catalog.json' \
  --out 'build/generated/regions/korea_sido_sigungu_YYYYMMDD.json'
```

`--baseline`에는 저장소의 임의 샘플이 아닌 실제 운영 카탈로그를 지정한다. 기존 `aliases`·`sigunguAliases`를 승계하고 신규·삭제·개명 등의 변경 요약을 출력하므로 항상 지정한다. 새 별칭 후보는 자동으로 확정되지 않으며 검토 후 반영해야 한다.

별칭 확인 및 편집 명령은 다음과 같다. 편집 예시의 이름은 실제 검토한 값으로 바꾼다.

```bash
# 현재 별칭 확인
java -jar build/libs/region-catalog-cli-*.jar alias \
  --catalog 'build/generated/regions/korea_sido_sigungu_YYYYMMDD.json'

# 검토한 별칭 추가: 시도는 과거명=현재명, 시군구는 현재시도명|과거시군구명=현재시군구명
java -jar build/libs/region-catalog-cli-*.jar alias \
  --catalog 'build/generated/regions/korea_sido_sigungu_YYYYMMDD.json' \
  --sido '과거시도명=현재시도명' \
  --sigungu '현재시도명|과거시군구명=현재시군구명'
```

승계된 별칭도 새 카탈로그에 존재하는 이름을 가리키는지 확인한다. 하나의 지역이 여러 지역으로 분리되는 1:N 변경은 별칭 하나로 연결하지 않고 기존 변경 대상 CSV로 개별 판정한다.

### 전국 법정동 CSV로 생성

국토교통부 전국 법정동 CSV를 사용하는 경우 아래 수동 변환 테스트를 실행한다. 입력은 UTF-8 또는 UTF-8 BOM이며 다음 헤더 형식이어야 한다.

```text
법정동코드,시도명,시군구명,읍면동명,리명,순위,생성일자,삭제일자
```

```bash
REGION_CSV_PATH='/path/to/국토교통부_전국 법정동_YYYYMMDD.csv' \
REGION_JSON_PATH='build/generated/regions/korea_sido_sigungu_YYYYMMDD.json' \
./gradlew test --tests '*RegionCatalogCsvConversionTest' --rerun-tasks
```

환경변수만 바뀌어도 실제 변환이 실행되도록 `--rerun-tasks`를 지정한다. CSV 변환에는 `--baseline` 승계 기능이 없으므로 기존 운영 JSON의 별칭을 검토해 별도로 반영한다. 위 CLI의 `alias` 명령은 CSV로 생성한 JSON에도 사용할 수 있다.

### 생성 결과 확인과 배포 연결

- 출력 JSON은 `meta`, `sido`와 선택적 `aliases`, `sigunguAliases` 구조다. 시도·시군구 코드와 이름, 개수, 별칭을 확인한다. 생성 명령은 운영 중인 외부 파일을 직접 교체하지 않는다.
- 일반구는 제외하고 상위 시에 포함하며, 세종은 하위 시군구 없이 시도로 포함한다. 제주 행정시는 포함한다. 실제 개수는 원본에 따라 달라지므로 고정값으로 맞추지 않는다.
- 별칭 편집까지 끝낸 **최종 파일**의 해시를 계산한다.

```bash
shasum -a 256 'build/generated/regions/korea_sido_sigungu_YYYYMMDD.json'
```

이 최종 파일을 양쪽 서버에 같은 바이트로 배포하고 아래 [반영 순서](#반영-순서)에 따라 reload한다. 모든 인스턴스의 status 응답 `active.sha256`이 위 해시와 같아야 한다. 시행 전 검토용 파일은 wabd의 차기 경로에만 배치하고 시행 시점에 현재 카탈로그로 전환한다.

변환 규칙과 전체 예시는 wabd 저장소의 `README.md` 내 “시도·시군구 JSON 생성”, “KIKmix xlsx로 카탈로그 생성” 절과 `docs/region-change-rehearsal.md`의 “1단계. 서빙 JSON 생성”에 기록되어 있다.

## 설정

최초 기능 배포 때 아래 환경변수를 설정하고 재기동한다. 토큰은 배포 비밀 설정으로 주입한다.

```text
REGION_CATALOG_SIDO_SIGUNGU_RESOURCE=file:/opt/welfare/regions/current.json
REGION_CATALOG_RELOAD_ENABLED=true
REGION_CATALOG_RELOAD_TOKEN=<운영 비밀 값>
REGION_CATALOG_INSTANCE_ID=<고유 인스턴스 이름>
```

저장소 기본 설정은 reload 비활성화다. 로컬에서 사용하려면 `REGION_CATALOG_RELOAD_ENABLED=true`, `REGION_CATALOG_RELOAD_TOKEN`, `REGION_CATALOG_SIDO_SIGUNGU_RESOURCE=file:<외부 JSON 경로>`를 지정하고 시작한다. 배포 환경에서는 외부 JSON의 절대 경로를 지정한다. 활성화 상태에서 classpath 리소스 또는 빈 토큰은 기동 오류다. 인스턴스 ID 미설정 시 호스트명을 사용한다. 초기 파일 로딩이 실패하면 기동 실패하며 번들 파일로 대체하지 않는다.

wabd는 `REGION_CATALOG_NEXT_SIDO_SIGUNGU_RESOURCE=file:/opt/welfare/regions/next.json`이 설정돼 있으면 현재·차기를 함께 읽는다. 차기만 잘못돼도 둘 다 교체하지 않는다. 현재·차기 경로 변경과 차기 추가·제거는 YAML 수정 후 reload로 반영하며 자동 승격은 없다. 차기를 사용하지 않으면 해당 변수를 비워 둔다.

## API

| 작업 | 경로 |
|---|---|
| 설정과 JSON 다시 읽기 | `POST /api/wsbd/internal/regions/reload` |
| 메모리 적용 상태 | `GET /api/wsbd/internal/regions/status` |

wabd는 `/api/wabd`를 사용한다. 두 API는 `Authorization: Bearer <운영 토큰>`이 필요하다. POST 본문은 없다.

reload마다 실행 중인 애플리케이션의 설정 위치와 활성 프로필을 기준으로 설정 파일을 다시 읽고 `region.catalog.sido-sigungu-resource`에 지정된 JSON을 읽는다. wabd는 `region.catalog.next-sido-sigungu-resource`도 함께 읽는다. JSON 파일을 새 이름으로 배포한 뒤 YAML의 경로를 변경하고 POST를 호출한다. 차기 경로 추가·변경·제거도 반영된다. 파일 경로는 `file:` 리소스여야 한다.

환경변수·실행 인자가 YAML보다 우선하는 규칙은 그대로 유지된다. 예를 들어 `REGION_CATALOG_SIDO_SIGUNGU_RESOURCE`를 설정했다면 YAML placeholder의 기본값만 바꿔도 환경변수 값이 우선한다. 실행 중인 프로세스의 환경변수를 외부 셸에서 바꿀 수는 없다. reload가 토큰·DB 설정 등 다른 애플리케이션 설정이나 빈을 갱신하지는 않는다.

Gradle/IDE가 이 프로젝트의 `build/resources/main/application.yml` 복사본으로 실행되는 경우, reload는 대응하는 `src/main/resources`의 YAML을 직접 읽는다. 따라서 소스 YAML의 JSON 경로만 수정하고 reload하면 된다. 외부 설정 파일·프로필·환경변수·실행 인자의 우선순위는 유지된다. 이 코드 변경을 실행 중인 서버에 적용할 때는 한 번 재시작해야 한다.

JAR 실행에서는 외부 application.yml을 수정한다. JAR 안에 묶인 YAML은 소스 파일을 수정해도 바뀌지 않는다.

```bash
# 로컬 실행 (IDE에서 직접 실행해도 동일)
./gradlew bootRun

# YAML의 region.catalog.sido-sigungu-resource를 새 file: 경로로 수정한 뒤 호출
curl --fail-with-body -X POST \
  'http://localhost:8081/api/wsbd/internal/regions/reload' \
  -H "Authorization: Bearer ${REGION_CATALOG_RELOAD_TOKEN}"
```

응답의 `active.sha256`으로 적용 내용을 확인한다. `active.sourceFile`은 JSON 메타데이터의 원본 자료명이다. 동일 내용이면 `UNCHANGED`, 변경되면 `RELOADED`를 반환한다. GET은 파일을 다시 읽지 않고 메모리 상태를 반환한다.

설정 오류는 422(`REGION_CATALOG_CONFIG_INVALID`), JSON 검증 오류는 422, 파일 읽기 실패는 503이다. 인증 실패 401, 동시 reload 409, 데이터 구성 실패 500은 기존과 같다. 설정·현재 JSON·차기 JSON 중 하나라도 실패하면 기존 정상 데이터를 유지한다. 각 인스턴스에 개별 호출해야 한다.

## 반영 순서

1. 새 JSON을 생성하고 diff를 검토한다. 양쪽 서버에 동일한 바이트의 현재 파일을 배포한다. 해시는 `shasum -a 256 current.json`으로 확인한다.
2. 이전 파일과 해시를 백업하고 배포·reload 작업을 직렬화한다.
3. 정책 지역 변경이 있다면 싱크·재처리 등 쓰기 작업을 중지하고 진행 중인 작업의 종료를 확인한다. 혼합 버전을 허용할 수 없다면 조회 트래픽도 점검 상태로 둔다.
4. 같은 디렉터리에 새 버전 JSON을 배포하고 `application.yml`의 카탈로그 경로를 새 파일로 수정한다. 컨테이너는 디렉터리 마운트로 교체된 파일이 보이는지 확인한다. 파일 크기 상한은 각각 5 MiB다.
5. wabd 인스턴스별 reload 및 해시 확인을 수행한다. 카탈로그, 정규화기, 현재→차기 CSV 매핑이 함께 교체된다.
6. 정책 변경이 있으면 기존 개편 절차대로 **CSV 적용 → 외부 정책 재처리**를 수행한다. reload 자체는 정책 DB를 수정하지 않는다.
7. wsbd 인스턴스별 reload 및 해시 확인을 수행한다. 지역 목록과 정책 조회 조건 검증에 새 데이터가 적용된다.
8. 공개 지역 조회·신규 지역 검색 결과 확인 후 트래픽과 스케줄러를 재개한다.

각 요청·배치는 시작 시 확보한 데이터를 끝까지 사용하므로 실행 중 배치는 reload 뒤에도 옛 지역을 저장할 수 있다. 운영 전환 중 배치 중지 절차를 생략하지 않는다. 카탈로그 rollback은 YAML 경로를 이전 파일로 되돌리고 모든 인스턴스를 reload한다. 정책 DB까지 바꿨다면 DB 복구·재처리도 별도로 수행해야 한다.

## 인스턴스 호출 스크립트

단일 인스턴스에서 curl로 리로드와 적용 상태 조회를 한 번에 실행하려면 다음 스크립트를 사용한다. 서버와 동일한 토큰을 환경변수로 설정한 뒤 실행하며, 로컬에서 생성한 토큰 파일이 있으면 아래처럼 불러올 수 있다.

```bash
# 저장소 루트에서 실행
source build/local/region-catalog-reload.env
bash scripts/reload-region-catalog.sh

# 다른 서버: 해당 서버의 토큰을 설정한 뒤 인스턴스 주소 지정
bash scripts/reload-region-catalog.sh https://instance-host/api/wsbd
```

기본 주소는 `http://localhost:8081/api/wsbd`다. POST 응답과 GET 응답을 차례로 출력하며, 리로드 실패 시에도 현재 상태를 조회한다. 어느 요청이든 HTTP 200이 아니거나 통신에 실패하면 종료 코드 1을 반환한다. 자동 재시도·redirect·파일 업로드는 하지 않으며, 서버에 새 JSON을 미리 배포해야 한다. application.yml의 카탈로그 경로를 수정한 뒤 실행한다. 응답 간 인스턴스 ID·해시 자동 대조가 필요하면 아래 Python 스크립트를 사용한다.

두 저장소의 `scripts/reload-region-catalog.py`는 Python 표준 라이브러리만 사용한다. 각 인스턴스의 POST와 GET 응답 ID·해시를 대조하고 하나라도 실패하면 종료 코드 1을 반환한다. HTTP redirect를 따라가지 않고 토큰과 서버 오류 본문을 출력하지 않는다. 자동 파일 배포·복구는 수행하지 않는다.

대상 JSON 예시(해시는 실제 64자리 값으로 교체):

```json
[
  {
    "application": "wsbd",
    "instanceId": "wsbd-01",
    "url": "https://instance-host/api/wsbd",
    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "nextSha256": null,
    "tokenEnv": "REGION_CATALOG_RELOAD_TOKEN"
  }
]
```

```bash
python3 scripts/reload-region-catalog.py /secure/region-targets.json --timeout 30
```

서버별 토큰이 다르면 `tokenEnv`에 각 환경변수 이름을 지정한다. wabd 차기가 설정돼 있으면 `nextSha256`도 명시한다. 개편 시 정책 DB 처리 전후에 각각 실행할 수 있도록 wabd와 wsbd 대상 파일을 나눈다. 대상 파일에는 운영 대상 모든 인스턴스를 명시해야 하며 스크립트가 인스턴스를 자동 탐색하지 않는다. 신규 인스턴스·재배포도 새 파일을 받도록 배포 원본까지 갱신한다.

파일명 전환은 각 서버의 application.yml 수정 후 수행한다. 차기 카탈로그를 전환하면 대상 검증 값 `nextSha256`도 갱신한다.
