# 🚇 LinkRo Backend

LinkRo의 Backend Repository입니다.

Spring Boot(Java)와 PostgreSQL을 기반으로  
지하철 경로 탐색, AI 중간역 추천, 향후 시간대별 혼잡도 예측, AI 노선 방향 안내, 약속방(위치 공유) 등의 서버 로직과 API를 구현합니다.

---

## 🛠 Tech Stack

### Backend

![Java](https://img.shields.io/badge/Java-17-007396?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)

### Database

![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white)

### Infra

![AWS Lightsail](https://img.shields.io/badge/AWS_Lightsail-FF9900?style=flat-square&logo=amazonaws&logoColor=white)
![Ubuntu](https://img.shields.io/badge/Ubuntu-E95420?style=flat-square&logo=ubuntu&logoColor=white)

### Collaboration

![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white)
![Notion](https://img.shields.io/badge/Notion-000000?style=flat-square&logo=notion&logoColor=white)

---

## ✨ 주요 기능

- 지하철 경로 탐색 API
- AI 중간역 추천 (자연어 조건 해석 연동)
- 향후 시간대별 열차 혼잡도 예측
- AI 노선 방향 안내 (표지판 이미지 분석 연동)
- 약속방 및 참여자 위치 공유

---

## 📁 Project Structure

```text
src/main/java/com/mju/linkro/
├── route/              # 경로 탐색
├── midstation/         # AI 중간역 추천
├── congestion/         # 혼잡도 예측
├── direction/          # AI 노선 방향 안내
├── promiseroom/        # 약속방 및 위치 공유
├── user/               # 사용자
└── common/             # 공통 설정 및 유틸
```

> 프로젝트 진행에 따라 폴더 구조는 변경될 수 있습니다.

---

## 🚀 Getting Started

### 1. Repository Clone

```bash
git clone https://github.com/TeamProject1-MJU/BE.git
```

### 2. 프로젝트 폴더 이동

```bash
cd BE
```

### 3. 환경 변수 설정

`.env.example`을 복사하여 `.env` 파일을 생성하고 값을 채웁니다.

macOS / Linux:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

- 로컬 실행(IntelliJ, `./gradlew bootRun`) 시 Spring이 프로젝트 루트의 `.env`를 자동으로 읽으므로, IntelliJ 실행 구성에 환경 변수를 따로 입력하지 않아도 됩니다.
- OS 환경 변수가 `.env`보다 우선 적용됩니다.
- `.env` 값은 따옴표 없이 `KEY=value` 형식으로 작성합니다.

---

### 4. 프로젝트 빌드 및 실행

```bash
./gradlew bootRun
```

또는 IntelliJ에서 `LinkroApplication.java` 실행

---

## 🌐 실행 확인

서버 실행 후 아래 주소로 접속하여 정상 동작을 확인할 수 있습니다.

```text
http://localhost:8080
```

---

## 🔀 Branch Strategy

```text
main
 └── dev
      ├── feat/*
      ├── fix/*
      └── chore/*
```

- `main` : 최종 안정 버전
- `dev` : 개발 통합 브랜치
- `feat/*` : 기능 개발
- `fix/*` : 버그 수정
- `chore/*` : 설정 및 기타 작업

---

## 📝 Development Process

```text
Issue 생성
   ↓
작업 Branch 생성
   ↓
기능 구현
   ↓
Commit & Push
   ↓
Pull Request
   ↓
Code Review
   ↓
dev Merge
   ↓
작업 Branch 삭제
```

PR 본문에 아래와 같이 관련 이슈를 연결합니다.

```text
Closes #이슈번호
```

PR이 Merge되면 연결된 Issue가 자동으로 종료됩니다.

---

## 💬 Commit Convention

| Type | 설명 |
| --- | --- |
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `chore` | 환경 설정 및 기타 작업 |
| `refactor` | 코드 리팩토링 |
| `docs` | 문서 수정 |
| `style` | 코드 포맷팅 (기능 변경 없음) |

예시:

```text
feat: 경로 탐색 API 구현 (#3)
```

```text
chore: Spring Boot 프로젝트 초기 세팅 (#1)
```

---

## 👥 Backend Team

| 이름 | 역할 | GitHub |
| --- | --- | --- |
| 최인준 | Full-Stack · Team Leader | [cij041109-del](https://github.com/cij041109-del) |
| 장선호 | Backend | [jjangjjangsunho](https://github.com/jjangjjangsunho) |
| 변현근 | Backend | [byunkeun](https://github.com/byunkeun) |

---

## 🔗 Related Repository

### Frontend

[TeamProject1-MJU/FE](https://github.com/TeamProject1-MJU/FE)

---

<div align="center">

### Link your route, LinkRo 🚇

**지하철 이동의 여러 순간을 하나의 흐름으로 연결합니다.**

</div>

### 공통 응답 예외 처리 정책

- Success는 항상 true, Failure는 항상 false이며 팩토리 사용 방식은 유지합니다.
- details는 클라이언트에 공개 가능한 값만 담으며, null 키/값은 제거하고 불변 복사본을 보관합니다.
- 검증 오류가 동일 키에 여러 개 있으면 메시지 사전순으로 가장 앞선 값을 사용합니다. 클래스 레벨 검증에는 예약 키 `_global`을 사용합니다.
- 404/406은 NOT_FOUND/NOT_ACCEPTABLE을 반환합니다. 미매핑 MVC 상태는 기존 HTTP 상태와 헤더를 유지하면서 고정 INTERNAL_ERROR 코드를 사용합니다.
- 5xx BusinessException의 원인과 스택은 ERROR 로그에 기록하며 응답에는 ErrorCode의 공개 메시지만 반환합니다.
- Spring Security 인증/인가 예외는 JWT 필터 및 AuthenticationEntryPoint / AccessDeniedHandler에서 동일한 ApiResponse 실패 형식으로 반환합니다. 필터 예외는 GlobalExceptionHandler의 자동 처리에 의존하지 않습니다.

### 공통 JPA 기반

- LinkRo 명세에 따라 UUIDv7을 애플리케이션 객체 생성 시점에 할당하기 위해 uuid-creator를 사용하며, persist 시점 생성 방식은 사용하지 않습니다.
- 기존 운영 테이블에 `created_at` / `updated_at`의 `nullable=false` 설정을 적용할 때는 기존 데이터 처리와 컬럼 변경을 migration으로 관리해야 합니다. `users` 테이블은 Flyway `V1__create_users.sql`로 관리하며, 기존 DB는 baseline(V1)으로 처리되어 `default now()`가 적용되지 않습니다.
- DB 변경은 `db/migration`에 `V{번호}__설명.sql`로만 추가하며, 이미 적용된 마이그레이션 파일은 수정하지 않습니다.

### JWT 인증 (Spring Security)

- `SecurityFilterChain`의 `JwtAuthenticationFilter`가 `/api/v1/**`의 Access Token을 검증합니다. 기존 `JwtProvider`로 서명·만료를 확인하고 사용자의 존재 여부와 `deleted_at IS NULL`을 확인합니다.
- 인증된 UUID는 `SecurityContext`에 저장하고 기존 `@CurrentUser UUID userId`로 주입합니다. 방별 OWNER/MEMBER 권한은 각 기능에서 `meeting_members`를 조회합니다.
- 정확히 일치하는 POST `/api/v1/auth/kakao`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`은 공개이며 잘못되거나 만료된 Authorization 헤더도 무시합니다. Swagger UI와 `/v3/api-docs/**`도 공개입니다.
- 헤더 누락·비 Bearer·빈 Bearer는 `AUTH_REQUIRED`, 만료는 `TOKEN_EXPIRED`, 잘못된 토큰·탈퇴·미존재 사용자는 `TOKEN_INVALID`이며 모두 401 공통 JSON 응답입니다. Security 인가 거부는 `ACCESS_DENIED`(403)입니다.
- 세션을 사용하지 않으며 폼 로그인·HTTP Basic·CSRF는 비활성화합니다. 토큰은 Authorization 헤더 또는 요청 body로 전달하고 쿠키 인증을 사용하지 않습니다.
- CORS는 Security 체인에서 인증보다 먼저 처리합니다. `CORS_ALLOWED_ORIGINS`와 기존 메서드·헤더 정책, `allowCredentials=false`를 유지합니다.
- 외부 카카오 인증정보 없이 테스트: Windows `.\gradlew.bat test`, macOS/Linux `./gradlew test`. 테스트 프로필은 H2와 카카오 클라이언트 mock을 사용합니다.
