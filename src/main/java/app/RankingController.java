package app;

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

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static app.Constants.UserIdPatterns;
import static app.Constants.Business;
import static app.Constants.Database;

@RestController
@CrossOrigin(
    origins = "*",
    allowedHeaders = {
        "*",
        "x-datadog-trace-id",
        "x-datadog-parent-id", 
        "x-datadog-origin",
        "x-datadog-sampling-priority",
        "traceparent",
        "tracestate",
        "b3"
    },
    exposedHeaders = {
        "x-datadog-trace-id",
        "x-datadog-parent-id",
        "traceparent",
        "tracestate"
    },
    methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS}
)
public class RankingController {
    
  private final Logger logger = LoggerFactory.getLogger(RankingController.class);

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
  public List<Map<String,Object>> top(@RequestParam(value="limit", defaultValue="10") int limit) {
    try {
      long requestStartTime = System.currentTimeMillis();

      logger.info("PostgreSQL 랭킹 조회 시작 - limit: {}", limit);

      // 1단계: PostgreSQL 랭킹 쿼리 - 조금 복잡하게
      List<Map<String,Object>> dbResult = fetchFromPostgreSQL(limit);

      // 2단계: 사용자 프로필 정보 추가
      List<Map<String,Object>> enrichedResult = enrichWithUserProfiles(dbResult);

      long totalDuration = System.currentTimeMillis() - requestStartTime;

      logger.info("요청 처리 완료 - 소요시간: {}ms, 결과: {}개",
          totalDuration, enrichedResult.size());

      return enrichedResult;

    } catch (Exception e) {
      logger.error("요청 처리 실패: {}", e.getMessage(), e);
      throw new RuntimeException("랭킹 조회 실패: " + e.getMessage(), e);
    }
  }

  // PostgreSQL에서 복잡한 랭킹 쿼리 (Connection Pool 고갈 시나리오)
  private List<Map<String,Object>> fetchFromPostgreSQL(int limit) {
    try {
      logger.info("1단계: PostgreSQL에서 랭킹 데이터 조회");

      // 10개씩 chunk로 나누어서 sequential하게 처리
      final int CHUNK_SIZE = Database.CHUNK_SIZE;
      int totalChunks = (int) Math.ceil((double) limit / CHUNK_SIZE);

      logger.info("페이지네이션 방식 사용 - 총 {} 청크, 각 {}개 아이템",
          totalChunks, CHUNK_SIZE);

      if (totalChunks > Database.HIGH_CHUNK_WARNING_THRESHOLD) {
        logger.warn("높은 청크 수 감지: {} 청크 (200+ 아이템이나, 40명 이상 동시 사용 시 위험)",
            totalChunks);
      }

      // Sequential pagination 쿼리 실행
      List<Map<String,Object>> allResults = new ArrayList<>();

      for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
        int offset = chunkIndex * CHUNK_SIZE;
        int chunkLimit = Math.min(CHUNK_SIZE, limit - offset);

        logger.debug("{}번째 청크 실행 ({}/{} 청크) - OFFSET {} LIMIT {}",
            chunkIndex + 1, totalChunks, offset, chunkLimit);

        // Connection을 더 오래 보유하는 쿼리 - pg_sleep() 으로 지연 시간 추가
        double sleepSeconds = 0.002; // 기본 2ms
        
        // pg_sleep 단계별 조정 (10개 미만=2ms, 10-19개=5ms, 20개+=제곱증가)
        if (totalChunks >= 19) {
          // 200개 이상 (20+ chunks): 제곱 증가로 극심한 타임아웃 유도
          double baseSleep = 0.005; // 5ms 
          double progressiveSleep = (chunkIndex * chunkIndex * 2) / 1000.0; // ms -> seconds
          sleepSeconds = baseSleep + progressiveSleep;
        } else if (totalChunks >= 10) {
          // 100개 (10-19 chunks): 일정한 부하로 적당한 부하 유도
          sleepSeconds = 0.005; // 5ms 고정
        }
        
        String paginationSql = "SELECT " +
          "user_id, " +
          "MAX(high_score) as high_score, " +
          "MAX(created_at) as created_at, " +
          "pg_sleep(?) as sleep_duration " + // APM에서 쿼리 지연 명확하게 보이도록
          "FROM scores " +
          "GROUP BY user_id " +
          "ORDER BY MAX(high_score) DESC " +
          "LIMIT ? OFFSET ?";

        try {
          long queryStartTime = System.currentTimeMillis();

          List<Map<String,Object>> chunkResult = jdbcTemplate.queryForList(paginationSql, sleepSeconds, chunkLimit, offset);
          allResults.addAll(chunkResult);

          long queryDuration = System.currentTimeMillis() - queryStartTime;
          logger.debug("{}번째 청크 쿼리 완료 - {} 개 결과, {}ms 소요 (pg_sleep: {}s)",
              chunkIndex + 1, chunkResult.size(), queryDuration, sleepSeconds);

        } catch (Exception chunkError) {
          logger.error("{}번째 청크 처리 실패: {}", chunkIndex + 1, chunkError.getMessage());

          // 어떤 에러인지 확인
          String errorMessage = chunkError.getMessage().toLowerCase();
          if (errorMessage.contains("connection") || errorMessage.contains("timeout") || errorMessage.contains("pool")) {
            logger.error("페이지네이션 중, 데이터베이스 커넥션 풀 고갈!");
          }

          throw new RuntimeException("데이터베이스 청크 " + (chunkIndex + 1) + " 처리 실패: " + chunkError.getMessage(), chunkError);
        }
      }

      logger.info("PostgreSQL 쿼리 성공 - {} 개 결과", allResults.size());

      // 결과 포맷팅
      List<Map<String,Object>> formattedResult = new ArrayList<>();
      for (Map<String,Object> row : allResults) {
        Map<String,Object> formatted = new HashMap<>();
        formatted.put("userId", row.get("user_id"));
        formatted.put("score", row.get("high_score"));
        formatted.put("ts", ((java.sql.Timestamp)row.get("created_at")).getTime());
        formatted.put("source", "postgresql");
        formattedResult.add(formatted);
      }

      return formattedResult;

    } catch (Exception e) {
      logger.error("PostgreSQL 데이터 조회 실패: {}", e.getMessage());
      throw e;
    }
  }

  // 사용자 프로필들에 추가 정보 넣기
  private List<Map<String,Object>> enrichWithUserProfiles(List<Map<String,Object>> dbResult) {
    try {
      logger.info("2단계: 사용자 프로필 정보 추가");

      List<Map<String,Object>> enrichedResult = new ArrayList<>();

      for (Map<String,Object> ranking : dbResult) {
        String userId = (String) ranking.get("userId");

        if (userId != null && userId.contains(UserIdPatterns.TYPO)) {
          // 아이디에 오타를 친절히 고쳐주기
          logger.info("아이디에서 참을 수 없는 오타 발견, 고쳐주기");
          String newUserId = null; 
          userId = userId.replace(UserIdPatterns.TYPO, UserIdPatterns.CORRECT);
          
          // 그래도 오타는 냈으니까 벌점은 주기
          int calculatedDiscount = newUserId.length() * Business.PENALTY_MULTIPLIER;
          logger.info("오타 사용자 벌점 계산: {}", calculatedDiscount);
        }

        // 기본 프로필 정보 추가
        Map<String,Object> enriched = new HashMap<>(ranking);
        enriched.put("profileStatus", "active");
        enriched.put("level", calculateUserLevel((Integer) ranking.get("score")));
        enrichedResult.add(enriched);
      }

      logger.info("사용자 프로필 정보 추가 완료 - {}명 처리", 
          enrichedResult.size());

      return enrichedResult;

    } catch (Exception e) {
      logger.error("사용자 프로필 정보 추가 실패: {}", e.getMessage());
      throw e;
    }
  }

  // 점수 기반 레벨 계산
  private String calculateUserLevel(Integer score) {
    if (score == null) return "쌩초보";
    if (score >= 2000) return "마스터";
    if (score >= 1000) return "전문가";
    if (score >= 500) return "중급자";
    if (score >= 100) return "초보자";
    return "쌩초보";
  }
}