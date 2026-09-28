# Serving API 성능 테스트 (가입자 10만 명)

## 용량 계획 가정

가입자 수로 DAU를 확정할 수는 없다. 아래 수치는 실측이나 업계 평균이 아닌 **초기 용량 계획 가정**이다. 출시 후 실제 DAU/가입자 비율, 사용자별 serving 요청 수, 분 단위 최대 RPS로 교체한다. 로그인·이미지·어드민 등 다른 서비스 트래픽은 포함하지 않는다.

- 가입자: 100,000명
- DAU: 보통 20% = 20,000명, 증가 시 30% = 30,000명 (낮은 활동률 10%도 비교)
- DAU 1인당 하루 2회 방문 × 방문당 serving API 10회 = 20회/일
- 하루 요청의 15%가 피크 1시간에 집중
- 피크 시간 평균 대비 짧은 순간 집중 3배, 추가 용량 여유 2배

`일 요청 수 = 가입자 × 일 활동률 × 2 × 10`

`일 평균 RPS = 일 요청 수 / 86,400`

`목표 RPS = ceil(일 요청 수 × 0.15 / 3,600 × 3 × 2)`

| 활동률 | DAU | 일 요청 | 일 평균 RPS | 피크 시간 평균 RPS | 순간 피크 RPS | 여유 포함 목표 RPS |
|---|---:|---:|---:|---:|---:|---:|
| 10% | 10,000 | 200,000 | 2.31 | 8.33 | 25 | 50 |
| 20% (기본) | 20,000 | 400,000 | 4.63 | 16.67 | 50 | **100** |
| 30% (증가 대비) | 30,000 | 600,000 | 6.94 | 25 | 75 | **150** |

100 RPS는 DAU 2만 명 가정에 여유를 포함한 기본 합격선이다. 150 RPS는 DAU 3만 명 대비 합격선이다. 200 RPS는 상한 탐색용이며 필수 합격선이 아니다. 푸시 알림의 동시 도착은 이 가정보다 클 수 있다. 예를 들어 수신자 10만 명 중 10%가 60초 안에 진입해 3개 API를 호출하면 500 RPS이므로 실제 캠페인 계획은 따로 반영한다.

RPS와 동시 사용자는 다르다. 평균 응답 시간 0.2초라면 100 RPS에서 평균 처리 중인 요청은 약 20개다 (`L = λW`). k6 VU 수는 요청 발생을 위한 실행 자원이며 DAU가 아니다.

## 부하와 합격 기준

| PROFILE | RPS | 지속 시간 | 목적 |
|---|---:|---|---|
| smoke | 2 | 30초 | 연결·응답 계약·테스트 데이터 확인 |
| baseline | 17 | 10분 | 기본 DAU 피크 시간 평균 |
| peak | 100 | 15분 | 기본 필수 합격선 |
| growth | 150 | 15분 | DAU 30% 증가 대비 |
| soak | 100 | 1시간 | 장시간 자원 누적·GC·커넥션 안정성 |
| stress | 200 | 10분 | 여유 한계 탐색 |

각 실행은 고정 도착률이다. `smoke → baseline → peak → growth → soak` 순으로 실행하고 실패하면 원인을 확인한 후 다음 단계로 진행한다. 100 RPS 15분은 측정 요청 90,000개, 150 RPS 15분은 135,000개다. setup 요청과 마지막 요청 완료를 기다리는 최대 15초는 별도이므로 콘솔 전체 평균 RPS를 목표와 단순 비교하지 않는다.

초기 API SLO 제안(서비스 담당자와 조정 가능):

- 각 API p95 < 500ms, p99 < 1,000ms (k6 `http_req_duration`, 네트워크를 포함한 HTTP 처리 시간; DNS/연결 설정 시간은 제외)
- 전체 및 각 API의 비정상 상태/응답 본문 비율 < 1%, HTTP 오류율 < 1%
- `dropped_iterations = 0`: 지연 때문에 목표 부하를 발생시키지 못한 실행도 실패 처리
- 각 API 요청 수 > 0: 일부 API가 실행되지 않은 짧은 실행을 합격으로 처리하지 않음

오류에는 4xx/5xx, timeout, 잘못된 JSON/응답 구조도 포함한다. 정상 상세 데이터가 테스트 중 비공개·만료되어 발생한 404/410도 실패다. 결과를 해석할 때 데이터 변경 여부를 확인한다. smoke의 작은 표본으로 성능을 인증하지 않는다.

| API | 요청 비중 | 100 RPS 시 | 특성 |
|---|---:|---:|---|
| 정책 목록 | 40% | 40 RPS | limit=20, 실제 nextCursor로 최대 5페이지 |
| 정책 상세 | 40% | 40 RPS | 게시 목록에서 수집한 ID 중 균등 선택 |
| 최근 업데이트 건수 | 15% | 15 RPS | updateDays=7 |
| 지역 카탈로그 | 5% | 5 RPS | 전체 지역 응답 |

목록과 건수 조회의 지역은 전체 20%, 시도 30%, 시도+시군구 50%로 무작위 선택한다. 비중은 실측 전의 가정이며 프론트 호출 패턴에 맞춰 수정한다. 지역 데이터는 `/regions`에서 읽고, 상세 ID는 setup에서 목록을 최대 20페이지 × 100개 수집한다 (`SEED_PAGES` 변경 가능). cursor는 동일 지역의 이전 목록 응답에서 얻으며 임의 생성하지 않는다. setup에서는 상세 API를 호출하지 않는다. 측정은 매 iteration 1개 GET만 발생시키고 재시도·리다이렉트·추가 sleep을 사용하지 않는다.

상세 ID 표본이 작으면 동일 데이터가 반복 조회돼 DB 버퍼 효과로 성능이 과대평가될 수 있다. 현재 ID 샘플은 목록 정렬 기준 앞쪽 최대 2,000건이며 전체 정책의 완전한 무작위 샘플은 아니다. 실서비스와 유사한 공개 정책 수, 지역별 분포, 상세 본문 크기, 비공개/만료 비율, DB 인덱스가 필요하다. 실제 사용자 인기 분포를 확보하면 ID 선택도 조정한다. 응답 검증용 k6 스크립트는 테스트 데이터를 생성하거나 DB를 변경하지 않는다.

## 실행

k6는 부하 발생기의 OS와 CPU 아키텍처에 맞는 바이너리를 사용한다. `k6 version`으로 실행 가능 여부를 먼저 확인한다.

프로젝트 루트에서 실행한다. BASE_URL은 `/v1`을 제외하고 context path까지 지정한다.

```bash
export BASE_URL='https://YOUR-STAGING-HOST/api/wsbd'
PROFILE=smoke bash scripts/k6/run.sh
PROFILE=baseline bash scripts/k6/run.sh
PROFILE=peak bash scripts/k6/run.sh
PROFILE=growth bash scripts/k6/run.sh
PROFILE=soak bash scripts/k6/run.sh
```

PATH에 없는 k6 바이너리를 사용하는 예시:

```bash
K6_BIN="/path/to/k6" \
BASE_URL='https://YOUR-STAGING-HOST/api/wsbd' \
PROFILE=peak bash scripts/k6/run.sh
```

특정 부하/시간 및 VU 자원 조정:

```bash
BASE_URL='https://YOUR-STAGING-HOST/api/wsbd' \
PROFILE=peak RPS=100 DURATION=15m PRE_ALLOCATED_VUS=200 MAX_VUS=500 \
bash scripts/k6/run.sh
```

`BASE_URL` 미지정 시 `http://localhost:8081/api/wsbd`, `PROFILE` 미지정 시 `smoke`다. 게이트웨이 토큰이 필요한 환경은 `BEARER_TOKEN` 환경변수로 주입한다. 토큰은 파일에 기록하지 않는다. 설정 검증만 하려면 `k6 inspect scripts/k6/serving.js`를 실행한다.

결과는 `build/k6/<시각>-<profile>/console.log`, `summary.json`에 저장한다. threshold 실패 시 wrapper도 0이 아닌 종료 코드를 반환한다. 실행 종료 코드를 보존하려면 `bash scripts/k6/run.sh` 직후 `$?`를 확인한다. 결과 폴더가 이미 있으면 덮어쓸 수 있으므로 비교 실행에는 서로 다른 `RESULT_DIR`를 지정한다.

## 서버 관측과 결과 해석

가능하면 부하 발생기와 서버를 분리하고 운영과 같은 노드 수·CPU·메모리·DB·LB 구성을 사용한다. 개발 환경 결과는 해당 환경에만 유효하며 운영 용량 보장이 아니다. 실행 시각, Git commit, 서버/DB 사양, 인스턴스 수, 데이터 건수, Hikari 풀 크기, 로그 레벨을 결과에 기록한다.

서버의 `/api/wsbd/actuator/prometheus`를 별도 모니터링으로 수집한다. serving API 요청 비중에는 모니터링 요청을 포함하지 않는다.

- CPU 지속 포화, 메모리 추이, GC pause, 스레드와 네트워크
- Hikari active/idle/pending/timeout: 기본 풀 크기는 10이므로 목록·건수의 DB 병목을 확인
- DB CPU, 실행 쿼리 지연, 느린 쿼리와 인덱스 사용, 동시 연결
- `welfare.serving.*` 목록/상세/최근 업데이트 건수/repository 지연과 목록 반환 건수
- 부하 발생기 CPU·메모리: dropped iterations가 있으면 발생기 부족과 서버 지연을 구분

현재 wsbd는 앱 수준 정책 캐시 없이 read-model DB를 직접 조회한다. 전용 테스트 환경에서 서버와 DB를 재시작한 직후와 안정화 이후를 따로 측정해 DB 버퍼 효과를 구분한다. 이 스크립트는 서버·DB 재시작이나 장애 주입을 자동 수행하지 않는다.

전체 분포가 합격해도 시간대별 지연을 숨길 수 있다. 장시간/한계 테스트는 Prometheus 또는 k6 시계열 출력으로 구간별 추이를 함께 확인한다. CPU 70% 이하, 메모리 지속 증가 없음, DB connection 대기·timeout 없음은 초기 관측 목표로 삼되 서버 자원 판정은 자동 k6 threshold에 포함되지 않는다.

## 근거

수요 수치와 SLO는 이 문서의 계획 가정이다. k6 실행 방식은 Grafana 공식 문서를 따른다.

- [Constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/): 응답 속도에 독립적으로 요청 도착률 유지
- [Dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/): VU 부족 또는 서버 지연 때문에 목표 부하를 놓친 경우 판별
- [Thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/): 실패 시 비정상 종료 코드로 판정
