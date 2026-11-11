# 표지판 방향 확인 (#75)

## 기준

API 명세 v1.12 9.1(`POST /api/v1/ai/direction-check`, multipart), 상세설계서 3.2, `AiClient.readSign`(#73, `docs/ai-client-interface.md`). 인증 필요(명세 1장), 방 멤버 확인은 없다(명세에 roomId 없음). 저장하는 테이블이 없고 이미지는 메모리에서만 쓴다.

## 요청·응답

- 파트 `image`: JPG/PNG, 5MB 이하. 파일의 첫 바이트(JPEG `FF D8 FF`, PNG 시그니처)로 판단하고 클라이언트가 보낸 Content-Type은 믿지 않는다.
- 파트 `route`: JSON 문자열 `{"stationId":105,"lineId":2,"direction":"INNER","headsign":"사당 방면"}`. `direction`은 생략 가능(UP/DOWN/INNER/OUTER/LOOP), `headsign` 필수.
- 응답 `data`: `result`(CORRECT/WRONG/RETAKE), `reason`(CORRECT면 null), `recognized`(AI가 읽은 `stationName`·`lineName`·`headsign`, 못 읽은 값은 null), `expected`(요청 route를 DB 이름으로 바꾼 값), `message`.
- 판정은 AI 응답 그대로이며 DB 정답 방향과 다시 대조하지 않는다. DB는 stationId·lineId를 이름으로 바꾸는 데만 쓴다.

## 오류

| 상황 | 코드 |
| --- | --- |
| image 없음·빈 파일·JPG/PNG 아님·5MB 초과 | IMAGE_INVALID (400) |
| route 없음·JSON 아님·필드 오류, 존재하지 않는 lineId | VALIDATION_ERROR (400) |
| 존재하지 않는 stationId | STATION_NOT_FOUND (404) |
| AI 호출 429 / 그 밖의 실패 | AI_QUOTA_EXCEEDED (429) / AI_UNAVAILABLE (503) |

이미지·route 검사는 AI 호출 전에 한다. 업로드 크기 제한은 `spring.servlet.multipart.max-file-size=5MB`(Spring 기본 1MB)이고, 초과하면 `GlobalExceptionHandler`가 IMAGE_INVALID로 바꾼다.

## 응답 시간

AI 호출 시간은 로그 `Sign check: result=… ai=…ms`로 본다. 표지판 판독 설정(표지판 경로에만 적용):

- `OPENAI_VISION_READ_TIMEOUT` 기본 10s (예전 4s). 실제 모델 호출이 4s를 넘겨 `AI_UNAVAILABLE`이 났기 때문이다. 응답 목표 5초는 이 값보다 짧으며, 실측 지연은 모델·추론 강도에 따라 다르다.
- `OPENAI_VISION_REASONING_EFFORT` 기본 `low`. 추론 강도를 낮춰 지연을 줄인다. 비어 있으면 `low`, 추론을 끄려면 `none`. 정확도는 낮은 강도에서 RETAKE가 늘 수 있다(정답 사진을 다시 찍으라고 하는 쪽으로 틀린다).
