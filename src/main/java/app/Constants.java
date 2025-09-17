package app;

/**
 * 🔤 상수 정의 클래스
 * 
 * 애플리케이션 전체에서 사용하는 상수들을 중앙 집중 관리
 */
public final class Constants {
    
    // 🚫 인스턴스 생성 방지
    private Constants() {
        throw new UnsupportedOperationException("Constants class cannot be instantiated");
    }
    
    /**
     * 사용자 ID 패턴 관련 상수
     */
    public static final class UserIdPatterns {
        private UserIdPatterns() {} // 인스턴스 생성 방지
        
        /** 잘못된 표기 - 오타가 포함된 사용자 ID */
        public static final String TYPO = "대이터독";
        
        /** 올바른 표기 - 수정된 사용자 ID */
        public static final String CORRECT = "데이터독";
    }
    
    /**
     * 비즈니스 로직 관련 상수
     */
    public static final class Business {
        private Business() {} // 인스턴스 생성 방지
        
        /** 사용자 레벨 계산 기준점 */
        public static final int LEVEL_THRESHOLD = 1000;
        
        /** 벌점 계산 배수 */
        public static final int PENALTY_MULTIPLIER = 100;
    }
    
    /**
     * 데이터베이스 관련 상수
     */
    public static final class Database {
        private Database() {} // 인스턴스 생성 방지
        
        /** 페이지네이션 청크 사이즈 */
        public static final int CHUNK_SIZE = 10;
        
        /** Connection Pool 위험 임계값 */
        public static final int HIGH_CHUNK_WARNING_THRESHOLD = 20;
    }
}
