# AI 모듈 인터페이스 (#73)

`com.mju.linkro.ai.api.AiClient` 하나만 주입받아 쓴다. 구현은 `LINKRO_AI_MODULE`로 전환한다.

| 값 | 구현 | 비고 |
| --- | --- | --- |
| 미설정 / 빈 값 / `mock` | `MockAiClient` | 기동 시 WARN. 결정적 규칙 |
| `openai` | `OpenAiClient` | `OPENAI_API_KEY` 또는 `OPENAI_MODEL`이 비면 기동 실패 |

```java
ConditionInterpretation interpretCondition(ConditionRequest req);   // #74 조건 해석(문자열·숫자 원문만)
ExplanationResult explainRecommendation(ExplanationRequest req);     // #74 추천 설명: 기준(TIME/FARE/DISTANCE) 전부를 한 번에 받아 기준별 문장 반환(facts 수치만 사용)
SignReading readSign(SignImage image, SignContext ctx);              // #75 표지판 판독 + 판정(API 명세 9.1)
GeneratedText replyToChat(ChatReplyRequest req);                     // #76 채팅 답변
```

- 실패: `BusinessException(AI_QUOTA_EXCEEDED)`(429) 또는 `BusinessException(AI_UNAVAILABLE)`(그 외 전부). 재시도 없음, 폴백은 소비자 책임.
- `readSign`은 기대 경로(`SignContext`: 역·노선·방향(nullable)·행선지)를 AI에 보내고, AI 판정 `SignResult`(CORRECT/WRONG/RETAKE)와 `SignReason`, 읽은 역명·노선명·행선지(`recognized`)를 반환한다. 판정은 그대로 쓰며 DB와 다시 대조하지 않는다(명세 9.1). 불확실하면 RETAKE. result와 reason이 맞지 않는 응답은 `AI_UNAVAILABLE`.
- `SignReason`: WRONG → `LINE_NOT_ON_ROUTE`/`DIRECTION_MISMATCH`, RETAKE → `NOT_A_SIGN`/`UNREADABLE`/`MISSING_INFO`, CORRECT → null. 응답 `message` 문구는 #75가 reason으로 만든다.
- 이미지 형식·크기 검사(`IMAGE_INVALID`)는 #75 컨트롤러에서 AI 호출 전에 한다.
- 필드명은 팀 문서 용어를 따른다: 조건 해석은 API 명세 8.1 filter(`maxMinutes`, `maxTransfers`, `maxFareWon`, 제외 역 `excludeStationNames`, 개인별 한도 `memberLimits[{nickname, maxMinutes}]`), 표지판은 9.1(`result`, `reason`, `stationName`, `lineName`, `direction`, `headsign`), 채팅 메시지는 10장(`nickname`, `content`).
- Mock 규칙: `N분 이내`/`N원 이하`/`환승 없이`/`환승 N번 이하`/`<역> 빼고`/`<닉네임>는 N분` 해석, 표지판은 이미지 첫 바이트로 결정 — `0`(`SIGN_RETAKE_BYTE`) → RETAKE/UNREADABLE, `'W'`(`SIGN_WRONG_BYTE`) → WRONG/DIRECTION_MISMATCH, 그 외 CORRECT(ctx 그대로), 텍스트에 `__AI_FAIL__`/`__AI_QUOTA__`가 있으면 해당 예외.
- 설정: `OPENAI_MODEL`(기본값 없음, 비어 있으면 기동 실패, 텍스트·이미지 공용), `OPENAI_CONNECT_TIMEOUT`(2s), `OPENAI_READ_TIMEOUT`(8s), `OPENAI_VISION_READ_TIMEOUT`(10s), `OPENAI_VISION_REASONING_EFFORT`(기본 `low`. 표지판 판독 `readSign`에만 `reasoning.effort`로 보낸다. 비어 있으면 `low`가 유지되고, 추론을 끄려면 `none`. 모델이 지원하는 값만 가능하며, reasoning을 지원하지 않는 모델은 요청이 거절돼 `AI_UNAVAILABLE`이 된다), `OPENAI_EXPLAIN_READ_TIMEOUT`(6s, 추천 설명 `explainRecommendation`에만 적용, #99).
  - 운영은 서버 `~/BE/.env`에 넣으면 docker-compose가 넘긴다. 비어 있거나 없으면 위 기본값이다(빈 값은 바인딩되지 않아 `OpenAiProperties` 기본값이 남는다).
