# #73 AiClient 구현 계획

## 1. 인터페이스 (`com.mju.linkro.ai.api`)
```java
public interface AiClient {
    ConditionInterpretation interpretCondition(ConditionRequest req);   // #74 필터
    GeneratedText explainRecommendation(ExplanationRequest req);         // #74 설명
    SignReading readSign(SignImage image, SignContext ctx);              // #75
    GeneratedText replyToChat(ChatReplyRequest req);                     // #76
}
```
- 실패는 모두 `BusinessException(AI_UNAVAILABLE | AI_QUOTA_EXCEEDED)`. 폴백(템플릿)은 소비자 책임.
- record (불변, 생성자 검증):
  - `ConditionRequest(String conditionText, List<String> memberNicknames, List<String> stationNameHints)`
  - `ConditionInterpretation(Integer maxMinutes, Integer maxTransfers, Integer maxFareWon, List<String> excludeStationNames, List<MemberLimit> memberLimits)` + `MemberLimit(String nickname, Integer maxMinutes)` — API 명세 8.1 filter 항목명, 역명·닉네임 문자열만 반환(매핑·범위검증은 #74)
  - `ExplanationRequest(String criterion, String stationName, Map<String,Object> facts)` — 서버 계산 수치만 전달
  - `GeneratedText(String text)`
  - `SignImage(byte[] bytes, String contentType)` (메모리 전용, toString에서 바이트 제외)
  - `SignContext(String stationName, String lineName, DirectionCode direction /*nullable*/, String headsign)` — API 명세 9.1 route/expected`
  - `SignReading(SignResult result, SignReason reason, String stationName, String lineName, String headsign)` — API 명세 9.1 result/reason/recognized
  - `ChatTurn(String nickname, String content)`, `ChatReplyRequest(String question, List<ChatTurn> recentMessages, String roomContext)`

## 2. 패키지
```
ai/api      AiClient, records, SignResult, SignReason
ai/mock     MockAiClient, MockAiConfig
ai/openai   OpenAiClient, OpenAiConfig, OpenAiProperties, OpenAiErrorMapper, dto/(Responses API 최소 DTO)
resources/ai/schema/condition.json, sign.json
resources/ai/prompt/*.txt
```

## 3. 설정
```yaml
linkro:
  ai:
    module: ${LINKRO_AI_MODULE:mock}        # mock | openai
    openai:
      api-key: ${OPENAI_API_KEY:}
      model: ${OPENAI_MODEL:}               # 기본값 없음. openai에서 비어 있으면 기동 실패
      connect-timeout: 2s
      read-timeout: 8s
      vision-read-timeout: 4s
```
- `OpenAiProperties`는 `@ConfigurationProperties` + `@Validated`, openai 모드에서 빈 키면 기동 실패.
- `.env.example`: `LINKRO_AI_MODULE`은 주석(빈 값이면 mock이 안 잡힘). `OPENAI_API_KEY=`, `OPENAI_MODEL=` (빈 값, "never commit" 주석).

## 4. Mock
- `MockAiConfig`: `linkro.ai.module`이 없거나 `mock`이거나 빈 값. WARN `AI is MOCK ... Set LINKRO_AI_MODULE=openai`.
- `MockAiClient` 결정적 규칙:
  - 조건: 정규식으로 "N분 이내"→maxMinutes, "환승 없이"→0 등 최소 해석
  - 설명/채팅: 고정 템플릿 문장
  - 표지판: 첫 바이트 0 → RETAKE/UNREADABLE, 'W' → WRONG/DIRECTION_MISMATCH, 그 외 CORRECT(ctx 그대로)
  - 실패 재현: 텍스트에 `__AI_FAIL__`/`__AI_QUOTA__` 포함 시 해당 예외 (소비자 폴백 테스트용)

## 5. OpenAI 구현
- RestClient(Kakao 방식, 용도별 read-timeout 두 개의 RestClient). `POST /v1/responses`, Bearer 키.
- 조건/표지판: `json_schema strict` → Jackson → record. 설명/채팅: 텍스트 출력, 길이 상한 trim.
- 표지판: data URL `input_image`, `detail:"low"`.
- 재시도 0회(초기). 프롬프트는 "판독·해석·설명만, 수치 생성 금지" 명시.

## 6. 에러 매핑 (`OpenAiErrorMapper`)
- 429 → `AI_QUOTA_EXCEEDED`; 그 외 비2xx, 타임아웃, `RestClientException`, 파싱 실패, refusal, incomplete → `AI_UNAVAILABLE`.
- safe cause(클래스명만), 키·프롬프트·이미지 로그 금지. 이미지 사전 검사는 #75 컨트롤러(`IMAGE_INVALID`).

## 7. 테스트 (실제 OpenAI 호출 없음)
- `MockAiClientTest`: 규칙·실패 마커
- `OpenAiClientTest`(MockRestServiceServer): 성공 4종, 429, 500, 타임아웃, 깨진 JSON, refusal, 요청 바디에 schema·모델·data URL 포함 검증
- `AiModuleConfigTest`(ApplicationContextRunner): 미설정·빈 값→Mock, openai+키+모델→OpenAi, openai+빈 키 또는 빈 모델→실패
- `AiClientContract`(선택): 공통 계약(null 금지 등)

## 8. 파일 목록
- 신규: 위 패키지 클래스 ~14개, schema 2, prompt 4, 테스트 3~4, `docs/ai-client-interface.md`
- 수정: `application.yaml`(주석 블록+설정), `.env.example`
- 의존성 추가 없음 (RestClient 사용 시)

## 9. 수직 슬라이스 순서
1. api 패키지(인터페이스+record) + MockAiClient + MockAiConfig + 테스트 → #74~76 병렬 착수 가능
2. OpenAiProperties/Config/전환 테스트 + `.env.example`
3. `interpretCondition` + `explainRecommendation` OpenAI 구현 + 테스트
4. `readSign` vision 구현 + 테스트
5. `replyToChat` 구현 + 테스트
6. 인터페이스 문서, (선택) 키 있는 수동 live 테스트

## 10. 결정 (모두 확정)
1. **연동 방식**: **확정: RestClient** (의존성 0, Kakao 패턴, 테스트 용이). 이후 필요하면 OpenAiClient 내부만 교체.
2. **API 엔드포인트**: **확정: Responses API** (structured output·vision 통합).
3. **모델명**: **확정: 설정값 `OPENAI_MODEL`, 기본값 없음**. openai 모드에서 비어 있으면 키와 같이 기동 실패. 텍스트·이미지 모두 같은 모델(명세 9.1 "이미지 입력을 지원하는 모델, 모델명은 설정값").
4. **인터페이스 형태**: **확정: 용도별 메서드 4개** (프롬프트·스키마를 ai 모듈에 캡슐화).
5. **조건 해석 결과 범위**: **확정: 문자열만 반환, 매핑·범위검증은 #74 서버**.
6. **표지판 판정 위치**: **확정(A): AI가 CORRECT/WRONG/RETAKE와 reason을 판정하고 그 결과를 그대로 쓴다.** API 명세 9.1 그대로, 상세설계서 문장은 팀이 수정.
7. **재시도**: **확정: 클라이언트 0회. #76 비동기만 소비자 측에서 1회 검토**.
8. **타임아웃**: **확정: connect 2s, 텍스트 read 8s, vision read 4s** (env로 조정).
9. **401/403/400 매핑**: **확정: `AI_UNAVAILABLE` + ERROR 로그**. `EXTERNAL_API_ERROR`는 AI에서 미사용.
10. **openai 모드 + 빈 키 또는 빈 모델**: **확정: 기동 실패**.
11. **env 이름**: **확정: `LINKRO_AI_MODULE`, `OPENAI_API_KEY`, `OPENAI_MODEL`**.
12. **live 테스트**: **확정: 만들지 않음** (실제 OpenAI 호출 테스트 없음).
