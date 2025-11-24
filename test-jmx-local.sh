#!/bin/bash
# HikariCP JMX 메트릭 로컬 테스트 스크립트

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "======================================"
echo "HikariCP JMX 메트릭 테스트"
echo "======================================"
echo ""

# JMXTerm 다운로드
if [ ! -f "/tmp/jmxterm.jar" ]; then
    echo "📥 JMXTerm 다운로드 중..."
    curl -sL https://github.com/jiaqi/jmxterm/releases/download/v1.0.4/jmxterm-1.0.4-uber.jar -o /tmp/jmxterm.jar
    echo "✅ JMXTerm 다운로드 완료"
    echo ""
fi

# Maven 빌드
echo "🔨 Maven 빌드 중..."
mvn clean package -DskipTests
echo "✅ 빌드 완료"
echo ""

# JMX 활성화해서 애플리케이션 실행
echo "🚀 ranking-java 실행 중 (JMX 포트: 9012)..."
echo ""
echo "⚠️  주의: PostgreSQL이 실행 중이어야 합니다!"
echo "   docker-compose up -d postgres 또는"
echo "   kubectl port-forward svc/postgres 5432:5432"
echo ""

# 백그라운드로 실행
JAVA_TOOL_OPTIONS="-Dcom.sun.management.jmxremote \
-Dcom.sun.management.jmxremote.authenticate=false \
-Dcom.sun.management.jmxremote.ssl=false \
-Dcom.sun.management.jmxremote.local.only=false \
-Dcom.sun.management.jmxremote.port=9012 \
-Dcom.sun.management.jmxremote.rmi.port=9012 \
-Djava.rmi.server.hostname=127.0.0.1" \
java -jar target/ranking-java.jar > /tmp/ranking-java.log 2>&1 &

APP_PID=$!
echo "✅ 애플리케이션 PID: $APP_PID"
echo "   로그: /tmp/ranking-java.log"
echo ""

# 애플리케이션 시작 대기
echo "⏳ 애플리케이션 시작 대기 중..."
sleep 10

# JMX 연결 확인
if ! nc -z 127.0.0.1 9012 2>/dev/null; then
    echo "❌ JMX 포트(9012)에 연결할 수 없습니다"
    echo "   로그를 확인하세요: tail -f /tmp/ranking-java.log"
    kill $APP_PID 2>/dev/null || true
    exit 1
fi

echo "✅ JMX 포트 연결 확인 완료"
echo ""
echo "======================================"
echo "📊 JMXTerm으로 HikariCP Bean 확인"
echo "======================================"
echo ""

# JMXTerm 스크립트
cat > /tmp/jmx-commands.txt <<'EOF'
# HikariCP 도메인 확인
domains

# HikariCP 도메인 선택
domain com.zaxxer.hikari

# Bean 목록 확인
beans

# 종료
exit
EOF

echo "🔍 현재 등록된 HikariCP Bean 확인..."
echo ""
java -jar /tmp/jmxterm.jar -l 127.0.0.1:9012 -n -i /tmp/jmx-commands.txt

echo ""
echo "======================================"
echo "📝 분석"
echo "======================================"
echo ""
echo "현재 application.properties에는:"
echo "  ❌ spring.datasource.hikari.pool-name 설정 없음"
echo "  ❌ spring.datasource.hikari.register-mbeans 설정 없음"
echo ""
echo "따라서 다음 Bean만 보일 것입니다:"
echo "  com.zaxxer.hikari:name=dataSource,type=HikariDataSource"
echo ""
echo "이 Bean에는 메트릭 속성(ActiveConnections 등)이 없습니다!"
echo ""
echo "======================================"
echo "🎯 해결 방법"
echo "======================================"
echo ""
echo "application.properties에 다음 2줄 추가:"
echo ""
echo "  spring.datasource.hikari.pool-name=RankingHikariPool"
echo "  spring.datasource.hikari.register-mbeans=true"
echo ""
echo "추가 후 다시 실행하면:"
echo "  ✅ com.zaxxer.hikari:type=Pool (RankingHikariPool) Bean 생성"
echo "  ✅ ActiveConnections, IdleConnections 등 메트릭 노출"
echo ""
echo "======================================"
echo "🛠️  대화형 JMXTerm 사용"
echo "======================================"
echo ""
echo "직접 명령어를 실행하려면:"
echo "  java -jar /tmp/jmxterm.jar -l 127.0.0.1:9012"
echo ""
echo "유용한 명령어:"
echo "  domains                    # 모든 도메인 목록"
echo "  domain com.zaxxer.hikari   # HikariCP 도메인 선택"
echo "  beans                      # Bean 목록"
echo "  bean <bean-name>           # Bean 선택"
echo "  info                       # Bean 정보 (속성, 메서드)"
echo "  get ActiveConnections      # 메트릭 값 조회"
echo ""
echo "======================================"

# 종료 안내
echo ""
echo "애플리케이션을 종료하려면:"
echo "  kill $APP_PID"
echo ""
echo "또는 Ctrl+C를 누르면 자동으로 종료됩니다."
echo ""

# trap으로 종료 시 정리
cleanup() {
    echo ""
    echo "🛑 애플리케이션 종료 중..."
    kill $APP_PID 2>/dev/null || true
    echo "✅ 종료 완료"
}

trap cleanup EXIT INT TERM

# 로그 출력
echo "📋 애플리케이션 로그 (Ctrl+C로 종료):"
echo "======================================"
tail -f /tmp/ranking-java.log

