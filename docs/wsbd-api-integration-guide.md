## 1. API 요약

| Method | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/wsbd/v1/regions` | 전체 시도·시군구 목록 조회 |
| `GET` | `/api/wsbd/v1/welfare-policies` | 게시 정책 목록 cursor 조회 |
| `GET` | `/api/wsbd/v1/welfare-policies/{id}` | 게시 정책 상세 조회 |

### 공통 요청 헤더

모든 wsbd API는 요청 추적을 위한 `X-TRACE-ID` 헤더를 선택값으로 받는다. Swagger UI에서도 각 API의 Header 파라미터로 입력할 수 있다.

| 헤더 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---:|---|---|
| `X-TRACE-ID` | string | N | 빈 값 | App·Backend·wsbd 호출 로그를 연결하는 요청 추적 ID |

## 2. 지역 목록 조회

### 요청

```http
GET /api/wsbd/v1/regions
```

```bash
curl '{WSBD_ORIGIN}/api/wsbd/v1/regions' \
  -H 'Accept: application/json' \
  -H 'X-TRACE-ID: 9f53cd97-5e79-47da-8f65-5d95a4733dc8'
```

### 성공 응답 `200 OK`

```json
{
  "sido": [
    {
      "sidoName": "서울특별시",
      "sigungu": [
        { "sigunguName": "종로구" },
        { "sigunguName": "중구" },
        { "sigunguName": "마포구" }
      ]
    },
    {
      "sidoName": "경기도",
      "sigungu": [
        { "sigunguName": "수원시" },
        { "sigunguName": "성남시" }
      ]
    }
  ]
}
```

응답은 전체 목록이며 위 예시는 일부만 표시한 것이다.

| 필드 | 타입 | 필수 | 설명 |
|---|---|---:|---|
| `sido` | array | Y | 시도 목록 |
| `sido[].sidoName` | string | Y | 시도명 |
| `sido[].sigungu` | array | Y | 해당 시도의 시군구 목록 |
| `sido[].sigungu[].sigunguName` | string | Y | 시군구명 |

## 3. 정책 목록 조회

### 요청

```http
GET /api/wsbd/v1/welfare-policies?sido={시도명}&sigungu={시군구명}&cursor={cursor}&limit={건수}
```

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---:|---|---|
| `sido` | string | N | 없음 | 시도명. 시도·시군구를 모두 생략하면 전체 조회 |
| `sigungu` | string | N | 없음 | 시군구명. 시도만 입력하고 생략하면 해당 시도 전체 조회 |
| `cursor` | string | N | 없음 | 이전 응답의 `nextCursor`. 첫 페이지는 생략 |
| `limit` | integer | N | `20` | 페이지당 건수, `1~100` |

### 지역 조회 범위

`sido`와 `sigungu`를 모두 생략하면 지역 필터 없이 전체 정책이 포함된다.

`sido=서울특별시`이고 `sigungu`를 생략하면 다음 정책이 포함된다.

- 전국 공통 정책: 시도·시군구가 모두 없는 정책
- 서울특별시 공통 정책: 시도는 서울특별시이고 시군구가 없는 정책
- 서울특별시 산하 모든 시군구 정책

`sido=서울특별시&sigungu=마포구`이면 다음 정책만 포함된다.

- 전국 공통 정책
- 서울특별시 공통 정책
- 서울특별시 마포구 정책

시도와 시군구 조합은 `/regions`에 존재해야 한다. 공백 문자열은 미입력으로 처리한다.

### 첫 페이지 요청 예시

```bash
curl --get '{WSBD_ORIGIN}/api/wsbd/v1/welfare-policies' \
  --data-urlencode 'sido=서울특별시' \
  --data-urlencode 'limit=20' \
  -H 'Accept: application/json' \
  -H 'X-TRACE-ID: 9f53cd97-5e79-47da-8f65-5d95a4733dc8'
```

### 성공 응답 `200 OK`

```json
{
  "items": [
    {
      "id": 94,
      "serviceName": "부모성장을 위한 심리지원서비스",
      "servicePurposeSummary": "임신부 또는 만 18세 이하 자녀를 둔 부모를 대상으로 심리지원서비스 제공",
      "competentAgency": "서울특별시 동작구",
      "sido": "서울특별시",
      "sigungu": "동작구"
    },
    {
      "id": 101,
      "serviceName": "임산부 교통비 지원",
      "servicePurposeSummary": "임산부의 이동 편의를 높이기 위해 교통비를 지원",
      "competentAgency": "서울특별시",
      "sido": "서울특별시",
      "sigungu": null
    }
  ],
  "nextCursor": "WyJ2MSIsMTc4NTgwMTYwMCwwLDAsMjA3NTcsMSwxNzg1NzE1MjAwLDAsOTRd",
  "hasNext": true
}
```

### 목록 항목 필드

| 필드 | 타입 | Null 가능 | 설명 |
|---|---|---:|---|
| `id` | integer(int64) | N | 정책 ID. 상세 API의 path 값으로 사용 |
| `serviceName` | string | N | 서비스명 |
| `servicePurposeSummary` | string | Y | 서비스 목적 요약 |
| `competentAgency` | string | Y | 소관기관명 |
| `sido` | string | Y | 정책 적용 시도명 |
| `sigungu` | string | Y | 정책 적용 시군구명 |

### Cursor 처리 규칙

- 첫 페이지 요청에는 `cursor`를 보내지 않는다.
- `hasNext=true`이면 응답의 `nextCursor`를 다음 요청의 `cursor`로 **그대로** 전달한다.
- `hasNext=false`이면 마지막 페이지이며 `nextCursor`는 `null`이다.
- Cursor를 App이나 Backend에서 생성, 수정, 복호화하거나 정책 ID로 대체하지 않는다.
- 다음 페이지에서도 최초 요청과 동일한 `sido`, `sigungu`, `limit`을 유지한다.
- Cursor에는 최초 조회시각과 정렬 기준이 포함되어 있어, 페이지 조회 도중 신규 정책이 게시돼도 기존 조회 흐름에 끼어들지 않는다.
- 화면을 새로고침할 때는 cursor 없이 첫 페이지부터 다시 조회한다.

### 다음 페이지 요청 예시

```bash
curl --get '{WSBD_ORIGIN}/api/wsbd/v1/welfare-policies' \
  --data-urlencode 'sido=서울특별시' \
  --data-urlencode 'limit=20' \
  --data-urlencode 'cursor=WyJ2MSIsMTc4NTgwMTYwMCwwLDAsMjA3NTcsMSwxNzg1NzE1MjAwLDAsOTRd' \
  -H 'Accept: application/json'
```

샘플 cursor는 문서 형식 설명용이다. 실제 연동에서는 반드시 직전 응답의 `nextCursor`를 사용한다.

## 4. 정책 상세 조회

### 요청

```http
GET /api/wsbd/v1/welfare-policies/{id}
```

```bash
curl '{WSBD_ORIGIN}/api/wsbd/v1/welfare-policies/94' \
  -H 'Accept: application/json' \
  -H 'X-TRACE-ID: 9f53cd97-5e79-47da-8f65-5d95a4733dc8'
```

### 성공 응답 `200 OK`

```json
{
  "id": 94,
  "serviceName": "부모성장을 위한 심리지원서비스",
  "servicePurposeSummary": "임신부 또는 만 18세 이하 자녀를 둔 부모를 대상으로 심리지원서비스 제공",
  "supportTarget": "기준 중위소득 140% 이하 가정의 임신부 또는 부모",
  "selectionCriteria": "서비스 욕구와 소득 기준을 종합하여 선정",
  "supportContent": "전문상담 서비스 및 사전·사후 평가 제공",
  "supportBenefitSummary": "기타(상담)",
  "supportType": "기타(상담)",
  "applicationMethod": "관할 동주민센터 방문 신청",
  "applicationDeadline": "접수기관 별 상이",
  "documents": "신분증, 신청서 및 대상자 증빙서류",
  "receivingAgencyName": "관할 동주민센터",
  "contact": "아동여성과/02-820-9259",
  "onlineApplicationUrl": "https://www.gov.kr/portal/rcvfvrSvc/main",
  "updatedDate": "2026-04-29",
  "competentAgency": "서울특별시 동작구",
  "administrativeRules": "사회서비스 이용권 관련 지침",
  "localOrdinances": "서울특별시 동작구 사회서비스 조례",
  "statutes": "사회서비스 이용 및 이용권 관리에 관한 법률"
}
```

### 상세 필드

| 필드 | 타입 | Null 가능 | 한글명/설명 |
|---|---|---:|---|
| `id` | integer(int64) | N | 정책 ID |
| `serviceName` | string | N | 서비스명 |
| `servicePurposeSummary` | string | Y | 서비스 목적 요약 |
| `supportTarget` | string | Y | 지원 대상 |
| `selectionCriteria` | string | Y | 선정 기준 |
| `supportContent` | string | Y | 지원 내용 |
| `supportBenefitSummary` | string | Y | 지원 내용 요약(혜택) |
| `supportType` | string | Y | 지원 유형 |
| `applicationMethod` | string | Y | 신청 방법 |
| `applicationDeadline` | string | Y | 신청 기한 |
| `documents` | string | Y | 구비서류 |
| `receivingAgencyName` | string | Y | 접수기관명 |
| `contact` | string | Y | 문의처 |
| `onlineApplicationUrl` | string | Y | 온라인 신청 사이트 URL |
| `updatedDate` | string(date) | Y | 정책 수정일, `YYYY-MM-DD` |
| `competentAgency` | string | Y | 소관기관명 |
| `administrativeRules` | string | Y | 관련 행정규칙 |
| `localOrdinances` | string | Y | 관련 자치법규 |
| `statutes` | string | Y | 관련 법령 |

## 5. 오류 응답

wsbd 오류는 admin-backend의 공통 오류 응답과 동일한 형식으로 응답한다.

```json
{
  "timestamp": "2026-08-04T10:30:00+09:00",
  "status": 400,
  "code": "INVALID_REGION",
  "message": "유효하지 않은 지역입니다.",
  "detail": null,
  "path": "/api/wsbd/v1/welfare-policies",
  "errors": []
}
```

| HTTP 상태 | code | 발생 조건 | Backend 권장 처리 |
|---:|---|---|---|
| `400` | `INVALID_REGION` | `/regions`에 없는 시도 또는 시군구 조합 | App에 입력 오류 전달 또는 지역 목록 갱신 |
| `400` | `INVALID_CURSOR` | cursor 형식/정렬 버전 오류 또는 `limit` 범위 오류 | 저장한 cursor 폐기 후 첫 페이지 재조회 |
| `404` | `POLICY_NOT_FOUND` | 해당 정책 ID가 DB에 없음 | 정책 없음 화면 처리 |
| `410` | `POLICY_NOT_AVAILABLE` | 정책은 존재하지만 미게시·노출 전·노출 종료 상태 | 더 이상 이용할 수 없는 정책 화면 처리 |

### 오류 예시: 잘못된 지역

```http
GET /api/wsbd/v1/welfare-policies?sido=서울특별시&sigungu=없는구
```

```json
{
  "timestamp": "2026-08-04T10:30:00+09:00",
  "status": 400,
  "code": "INVALID_REGION",
  "message": "유효하지 않은 지역입니다.",
  "detail": null,
  "path": "/api/wsbd/v1/welfare-policies",
  "errors": []
}
```

### 오류 예시: 만료되거나 비공개인 정책

```json
{
  "timestamp": "2026-08-04T10:30:00+09:00",
  "status": 410,
  "code": "POLICY_NOT_AVAILABLE",
  "message": "현재 이용할 수 없는 정책입니다.",
  "detail": null,
  "path": "/api/wsbd/v1/welfare-policies/94",
  "errors": []
}
```
