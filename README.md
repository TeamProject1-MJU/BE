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

![AWS EC2](https://img.shields.io/badge/AWS_EC2-FF9900?style=flat-square&logo=amazonec2&logoColor=white)
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

`src/main/resources/application-local.yml` 파일을 생성하고 아래 항목을 채워주세요.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/linkro
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

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

### Backend

[TeamProject1-MJU/FE](https://github.com/TeamProject1-MJU/BE)

---

<div align="center">

### Link your route, LinkRo 🚇

**지하철 이동의 여러 순간을 하나의 흐름으로 연결합니다.**

</div>
