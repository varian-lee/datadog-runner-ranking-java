package app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
/**
 * 🏆 Ranking Service (Java Spring Boot) - Datadog APM 디버깅 시나리오
 * 
 * PostgreSQL 전용 랭킹 서비스 - 자연스러운 Connection Pool 고갈 시나리오
 * 
 * 🔄 서비스 호출 체인:
 * 1. PostgreSQL 복잡한 랭킹 쿼리 (사용자별 최고 점수)
 * 2. 외부 API 호출 (사용자 프로필 조회)
 * 
 * 🚨 에러 시나리오:
 * - 40명 이상 동시 요청 또는 1명이 200개 요청: Connection Pool 고갈
 * - 특정 userId 포함 ('error'): 외부 API 호출 실패
 * 
 * 🔍 Datadog Live Debugging 활용:
 * - 각 단계별 변수 값 추적
 * - 에러 조건 분기점에서 디버깅 포인트 설정
 * - 분산 트레이싱으로 전체 요청 플로우 추적
 */
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.Constants.Business;
import app.Constants.Database;
import app.Constants.UserIdPatterns;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = {
    "*",
    "x-datadog-trace-id",
    "x-datadog-parent-id",
    "x-datadog-origin",
    "x-datadog-sampling-priority",
    "traceparent",
    "tracestate",
    "b3"
}, exposedHeaders = {
    "x-datadog-trace-id",
    "x-datadog-parent-id",
    "traceparent",
    "tracestate"
}, methods = { RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS })
public class RankingController {

  private final Logger logger = LoggerFactory.getLogger(RankingController.class);

  // UserIdPatterns 상수를 private 변수로 정의
  private String TYPO_PATTERN = UserIdPatterns.TYPO;
  private String CORRECT_PATTERN = UserIdPatterns.CORRECT;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  // 헬스체크 엔드포인트 - ALB 헬스체크용
  @GetMapping("/")
  public Map<String, Object> healthCheck() {
    Map<String, Object> response = new HashMap<>();
    response.put("status", "healthy");
    response.put("service", "ranking-java");
    return response;
  }

  @GetMapping("/rankings/top")
  public List<Map<String, Object>> top(@RequestParam(value = "limit", defaultValue = "10") int limit) {
    long requestStartTime = System.currentTimeMillis();

    logger.info("PostgreSQL 랭킹 조회 시작 - limit: {}", limit);

    // 1단계: PostgreSQL 랭킹 쿼리 - 조금 복잡하게
    List<Map<String, Object>> dbResult = fetchFromPostgreSQL(limit);

    // 2단계: 사용자 프로필 정보 추가
    List<Map<String, Object>> enrichedResult = enrichWithUserProfiles(dbResult);

    long totalDuration = System.currentTimeMillis() - requestStartTime;

    logger.info("요청 처리 완료 - 소요시간: {}ms, 결과: {}개",
        totalDuration, enrichedResult.size());

    return enrichedResult;
  }

  // PostgreSQL에서 복잡한 랭킹 쿼리 (Connection Pool 고갈 시나리오)
  private List<Map<String, Object>> fetchFromPostgreSQL(int limit) {
    try {
      logger.info("1단계: PostgreSQL에서 랭킹 데이터 조회~~~");

      // 10개씩 chunk로 나누어서 sequential하게 처리 - 일부러..
      final int CHUNK_SIZE = Database.CHUNK_SIZE;
      int totalChunks = (int) Math.ceil((double) limit / CHUNK_SIZE);

      logger.info("페이지네이션 방식 사용 - 총 {} 청크, 각 {}개 아이템",
          totalChunks, CHUNK_SIZE);

      if (totalChunks > Database.HIGH_CHUNK_WARNING_THRESHOLD) {
        logger.warn("높은 청크 수 감지: {} 청크 (200+ 아이템이나, 40명 이상 동시 사용 시 위험)",
            totalChunks);
      }

      // Sequential pagination 쿼리 실행
      List<Map<String, Object>> allResults = new ArrayList<>();

      for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
        int offset = chunkIndex * CHUNK_SIZE;
        int chunkLimit = Math.min(CHUNK_SIZE, limit - offset);

        logger.debug("{}번째 청크 실행 ({}/{} 청크) - OFFSET {} LIMIT {}",
            chunkIndex + 1, totalChunks, offset, chunkLimit);

        // Connection을 더 오래 보유하는 쿼리 - pg_sleep() 으로 지연 시간 추가
        double sleepSeconds = 0.3; // 기본 30ms

        // CTE를 사용한 개선된 pagination SQL - pg_sleep을 안전하게 분리
        String paginationSql = "WITH delay AS (SELECT pg_sleep(?)) " +
            "SELECT user_id, " +
            "MAX(high_score) AS high_score, " +
            "MAX(created_at) AS created_at " +
            "FROM scores " +
            "CROSS JOIN delay " +
            "GROUP BY user_id " +
            "ORDER BY MAX(high_score) DESC " +
            "LIMIT ? OFFSET ?";

        try {
          long queryStartTime = System.currentTimeMillis();

          List<Map<String, Object>> chunkResult = jdbcTemplate.queryForList(paginationSql, sleepSeconds, chunkLimit,
              offset);
          allResults.addAll(chunkResult);

          long queryDuration = System.currentTimeMillis() - queryStartTime;
          logger.debug("{}번째 청크 쿼리 완료 - {} 개 결과, {}ms 소요 (pg_sleep: {}s)",
              chunkIndex + 1, chunkResult.size(), queryDuration, sleepSeconds);

        } catch (Exception chunkError) {
          logger.error("{}번째 청크 처리 실패: {}", chunkIndex + 1, chunkError.getMessage());

          // 어떤 에러인지 확인
          String errorMessage = chunkError.getMessage().toLowerCase();
          if (errorMessage.contains("connection") || errorMessage.contains("timeout")
              || errorMessage.contains("pool")) {
            logger.error("페이지네이션 중, 데이터베이스 커넥션 풀 고갈!");
          }

          throw new RuntimeException("데이터베이스 청크 " + (chunkIndex + 1) + " 처리 실패: " + chunkError.getMessage(),
              chunkError);
        }
      }

      logger.info("PostgreSQL 쿼리 성공 - {} 개 결과", allResults.size());

      // 결과 포맷팅
      List<Map<String, Object>> formattedResult = new ArrayList<>();
      for (Map<String, Object> row : allResults) {
        Map<String, Object> formatted = new HashMap<>();
        formatted.put("userId", row.get("user_id"));
        formatted.put("score", row.get("high_score"));
        formatted.put("ts", ((java.sql.Timestamp) row.get("created_at")).getTime());
        formatted.put("source", "postgresql");
        formattedResult.add(formatted);
      }

      return formattedResult;

    } catch (org.springframework.dao.DataAccessException e) {
      logger.error("데이터베이스 접근 오류: {}", e.getMessage());
      throw new RuntimeException("데이터베이스 연결 실패: " + e.getMessage(), e);
    } catch (Exception e) {
      logger.error("PostgreSQL 데이터 조회 중 예상치 못한 오류: {}", e.getMessage());
      throw new RuntimeException("데이터 조회 실패: " + e.getMessage(), e);
    }
  }

  // 사용자 프로필들에 추가 정보 넣기
  @SuppressWarnings("null")
  private List<Map<String, Object>> enrichWithUserProfiles(List<Map<String, Object>> dbResult) {
    Tracer tracer = GlobalTracer.get();
    Span current = tracer.activeSpan();

    // 1️⃣ 기존 동작: 일반적인 자식 스팬으로 메인 로직 실행 (기존 트레이스에 연결)
    Span childSpan = tracer.buildSpan("enrichWithUserProfiles")
        .start();

    // 2️⃣ 추가: 독립적인 새 트레이스 생성 (병렬 실행)
    Span newRoot = tracer.buildSpan("ranking.enrich_user_profiles")
        .ignoreActiveSpan() // ★ 활성 스팬을 부모로 잡지 않음 → 새 트레이스 시작
        .start();

    try (Scope childScope = tracer.activateSpan(childSpan);
        Scope newTraceScope = tracer.activateSpan(newRoot)) {
      // 두 스팬 모두 활성화

      // (선택) 기존 스팬/트레이스 ID를 태그로 남겨서 대시보드/로그에서 상호 참조
      if (current != null) {
        newRoot.setTag("link.trace_id", current.context().toTraceId());
        newRoot.setTag("link.span_id", current.context().toSpanId());
      }

      // 독립 트레이스 태그 설정
      newRoot.setTag("component", "ranking-service");
      newRoot.setTag("operation.type", "user_profile_enrichment");
      newRoot.setTag("span.kind", "internal");
      newRoot.setTag("resource.name", "enrichWithUserProfiles");

      // 기존 자식 스팬 태그 설정
      childSpan.setTag("component", "ranking-service");
      childSpan.setTag("operation.type", "user_profile_enrichment");
      childSpan.setTag("span.kind", "internal");

      logger.info("2단계: 사용자 프로필 정보 추가");

      if (dbResult == null) {
        // 두 스팬 모두에 에러 정보 설정
        newRoot.setTag("error", true);
        newRoot.setTag("error.msg", "입력 데이터가 NULL입니다");
        childSpan.setTag("error", true);
        childSpan.setTag("error.msg", "입력 데이터가 NULL입니다");
        throw new IllegalArgumentException("입력 데이터가 NULL입니다");
      }

      // 입력 데이터 메트릭 추가 (두 스팬 모두에)
      newRoot.setTag("input.record_count", dbResult.size());
      childSpan.setTag("input.record_count", dbResult.size());

      List<Map<String, Object>> enrichedResult = new ArrayList<>();
      int typoFixCount = 0;

      for (Map<String, Object> ranking : dbResult) {
        String userId = (String) ranking.get("userId");

        if (userId != null && userId.contains(TYPO_PATTERN)) {
          // 아이디에 오타를 친절히 고쳐주기
          logger.info("아이디에서 참을 수 없는 오타 발견, 고쳐주기");
          String newUserId = userId.replace(TYPO_PATTERN, CORRECT_PATTERN);
          userId = newUserId; // 수정된 userId 적용
          typoFixCount++;

          // 그래도 오타는 냈으니까 벌점은 주기
          if (newUserId != null) {
            int calculatedDiscount = newUserId.length() * Business.PENALTY_MULTIPLIER;
            logger.info("오타 사용자 벌점 계산: {}", calculatedDiscount);
          } else {
            logger.warn("사용자 ID 수정 중 NULL 발생, 원본 사용");
          }
        }

        // 기본 프로필 정보 추가
        Map<String, Object> enriched = new HashMap<>(ranking);
        enriched.put("profileStatus", "active");
        enriched.put("level", calculateUserLevel((Integer) ranking.get("score")));
        enrichedResult.add(enriched);
      }

      // 처리 결과 메트릭 추가 (두 스팬 모두에)
      newRoot.setTag("output.record_count", enrichedResult.size());
      newRoot.setTag("typo_fixes_applied", typoFixCount);
      newRoot.setTag("success", true);

      childSpan.setTag("output.record_count", enrichedResult.size());
      childSpan.setTag("typo_fixes_applied", typoFixCount);
      childSpan.setTag("success", true);

      logger.info("사용자 프로필 정보 추가 완료 - {}명 처리",
          enrichedResult.size());

      return enrichedResult;

    } catch (IllegalArgumentException e) {
      logger.error("잘못된 입력으로 인한 프로필 처리 실패: {}", e.getMessage());
      throw new RuntimeException("입력 데이터 오류: " + e.getMessage(), e);
    } catch (NullPointerException e) {
      logger.error("NULL 참조로 인한 프로필 처리 실패: {}", e.getMessage(), e);
      throw new RuntimeException("데이터 무결성 오류 - NULL 참조: " + e.getMessage(), e);
    } catch (Exception e) {
      logger.error("사용자 프로필 정보 추가 중 예상치 못한 오류: {}", e.getMessage(), e);
      throw new RuntimeException("프로필 처리 실패: " + e.getMessage(), e);
    } finally {
      // 두 스팬 모두 수동으로 종료
      childSpan.finish();
      newRoot.finish();
    }
  }

  // 점수 기반 레벨 계산
  private String calculateUserLevel(Integer score) {
    if (score == null)
      return "쌩초보";
    if (score >= 2000)
      return "마스터";
    if (score >= 1000)
      return "전문가";
    if (score >= 500)
      return "중급자";
    if (score >= 100)
      return "초보자";
    return "쌩초보";
  }
}