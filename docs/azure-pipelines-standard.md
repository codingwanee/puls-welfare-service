# Azure Pipelines 표준

## 목적

이 저장소는 Gradle Wrapper로 테스트와 Spring Boot 실행 JAR를 빌드하고, 추적 가능한 개발·릴리스 산출물을 Azure Pipeline Artifact `drop`으로 게시한다. 프로젝트 버전의 단일 기준은 루트의 `VERSION` 파일이다.

## 버전 기준

`VERSION`은 prerelease 접미사가 없는 SemVer `MAJOR.MINOR.PATCH` 형식으로 관리한다. 현재 운영 기준 버전은 `1.0.0`이다. 로컬 빌드는 별도 property가 없으면 `1.0.0-SNAPSHOT`을 사용하고, Pipeline은 `-PbuildVersion`으로 계산된 버전을 주입한다.

| 입력 ref | 계산 버전 예시 | Drop 게시 |
|---|---|---|
| develop 대상 PR | `1.0.0-pr.48753.1301` | 아니요 |
| `refs/heads/develop` | `1.0.0-dev.1302` | 예 |
| `refs/heads/release/1.0.0` | `1.0.0-rc.1303` | 예 |
| `refs/heads/main` | `1.0.0-main.1304` | 예 |
| `refs/tags/v1.0.0` | `1.0.0` | 예 |
| 기타 수동 빌드 | `1.0.0-feature.<branch>.1305` | 아니요 |

`release/X.Y.Z`와 태그 `vX.Y.Z`의 버전은 반드시 `VERSION`과 같아야 한다. 정식 태그의 commit은 `origin/main`에 포함되어 있어야 한다. 같은 태그를 다시 빌드해도 Azure `BuildId`, 빌드 폴더, metadata로 실행을 구분한다.

## Pipeline 흐름

1. 전체 Git history와 tag를 checkout한다.
2. JDK 17을 선택한다.
3. Gradle Wrapper JAR의 고정 SHA-256과 Gradle distribution checksum 설정을 검증한다.
4. Gradle cache를 복원한다.
5. 전체 Git ref로 project version과 release channel을 계산한다.
6. `clean test bootJar`를 실행하고 CI 추적값을 manifest에 주입한다.
7. JUnit 결과를 게시한다.
8. 실행 가능한 JAR가 정확히 하나인지 확인한다.
9. JAR, SHA-256, `metadata.json`을 staging한다.
10. develop, main, release, 정식 태그 빌드만 Pipeline Artifact `drop`으로 게시한다.

PR 검증은 Azure Repos Branch Policy의 Build Validation으로 실행한다. Azure Repos용 YAML에는 `pr:` 블록을 두지 않는다. feature branch push를 CI trigger에 넣지 않아 PR validation과의 중복 실행을 피한다.

## JAR와 manifest

plain JAR task는 비활성화한다. Pipeline은 `*-plain.jar`, `*-tests.jar`, `*-test-fixtures.jar`를 제외한 JAR가 정확히 하나일 때만 진행한다.

실행 JAR manifest에는 다음 값을 기록한다.

- `Implementation-Title`
- `Implementation-Version`
- `Build-Id`
- `Build-Commit`
- `Build-Branch`

로컬 기본값은 각각 프로젝트 이름, `VERSION-SNAPSHOT`, `local`, `unknown`, `local`이다.

## Drop 구조

```text
drop/
└── <repository>/
    └── <projectVersion>/
        └── build-<BuildId>/
            ├── <repository>-<baseVersion>-<channel>-<KST-yyyyMMdd>-b<BuildId>.jar
            ├── <repository>-<baseVersion>-<channel>-<KST-yyyyMMdd>-b<BuildId>.jar.sha256
            └── metadata.json
```

develop 산출물의 `<channel>`은 `dev`, main은 `main`이다. Pipeline 실행 이름도 동일한 버전·채널·KST 일자·BuildId 순서를 사용한다.

`metadata.json`에는 repository, projectVersion, releaseChannel, buildDateKst, artifactFileName, buildId, buildNumber, buildUri, sourceBranch, sourceCommit, UTC 생성 시각, JAR SHA-256을 기록한다.

## 실패 조건

다음 중 하나라도 발생하면 Pipeline을 실패시킨다.

- `VERSION`이 `MAJOR.MINOR.PATCH` 형식이 아님
- Wrapper JAR checksum 불일치
- Wrapper distribution checksum 설정 누락 또는 불일치
- `release/X.Y.Z`와 `VERSION` 불일치
- `vX.Y.Z`와 `VERSION` 불일치
- 정식 태그 commit이 `origin/main`에 포함되지 않음
- 컴파일 또는 테스트 실패
- bootJar 생성 실패
- 실행 가능한 JAR가 정확히 하나가 아님
- Drop staging 또는 checksum 생성 실패

## Azure DevOps UI 후속 설정

YAML PR이 검증된 뒤 각 저장소에서 다음을 사람이 설정한다.

- YAML 파일을 선택해 Pipeline을 생성한다.
- `develop`과 `main`에 직접 push를 제한하고 PR을 사용한다.
- 두 브랜치에 해당 Pipeline을 Required, Automatic Build Validation으로 연결한다.
- 최소 승인자와 Required Reviewer 정책은 설정하지 않는다.
- 태그 생성 권한은 릴리스 담당자에게만 허용한다.
- Pipeline retention과 Artifact retention 기간을 결정한다.
- Pipeline job authorization scope를 현재 프로젝트로 제한한다.

기본 브랜치는 `develop`을 유지하고, 운영 승격은 `develop → main` PR로 수행한다. `main`에는 직접 push를 제한하고 Required Automatic Build Validation을 적용한다.

## 로컬 검증

```bash
./gradlew clean test bootJar --no-daemon --build-cache
```

CI manifest 주입 예시:

```bash
./gradlew clean test bootJar --no-daemon --build-cache \
  -PbuildVersion=1.0.0-dev.999 \
  -PbuildId=999 \
  -PbuildCommit=0123456789abcdef \
  -PbuildBranch=refs/heads/develop
```
