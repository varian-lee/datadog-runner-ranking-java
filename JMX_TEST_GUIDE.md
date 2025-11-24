# HikariCP JMX 로컬 테스트 가이드

## 빠른 시작

### 방법 1: 자동 스크립트 (추천)

```bash
cd ranking-java
./test-jmx-local.sh
```

스크립트가 자동으로:
1. JMXTerm 다운로드
2. Maven 빌드
3. JMX 활성화하여 애플리케이션 실행
4. HikariCP Bean 확인

### 방법 2: 수동 실행

#### 1단계: JMXTerm 다운로드

```bash
curl -L https://github.com/jiaqi/jmxterm/releases/download/v1.0.4/jmxterm-1.0.4-uber.jar -o /tmp/jmxterm.jar
```

#### 2단계: 애플리케이션 빌드

```bash
cd ranking-java
mvn clean package -DskipTests
```

#### 3단계: JMX 활성화하여 실행

```bash
export JAVA_TOOL_OPTIONS="-Dcom.sun.management.jmxremote \
-Dcom.sun.management.jmxremote.authenticate=false \
-Dcom.sun.management.jmxremote.ssl=false \
-Dcom.sun.management.jmxremote.local.only=false \
-Dcom.sun.management.jmxremote.port=9012 \
-Dcom.sun.management.jmxremote.rmi.port=9012 \
-Djava.rmi.server.hostname=127.0.0.1"

java -jar target/ranking-java.jar
```

⚠️ **주의**: PostgreSQL이 실행 중이어야 합니다!
```bash
# Kubernetes에 postgres가 있다면:
kubectl port-forward svc/postgres 5432:5432
```

#### 4단계: JMXTerm 접속 (다른 터미널)

```bash
java -jar /tmp/jmxterm.jar -l 127.0.0.1:9012
```

## JMXTerm 사용법

### 기본 명령어

```
$> domains
# 모든 JMX 도메인 목록 확인

$> domain com.zaxxer.hikari
# HikariCP 도메인 선택

$> beans
# HikariCP Bean 목록 확인
```

### 현재 상태 확인 (register-mbeans=false)

```
$> beans
#domain = com.zaxxer.hikari:
com.zaxxer.hikari:name=dataSource,type=HikariDataSource

$> bean com.zaxxer.hikari:name=dataSource,type=HikariDataSource
#bean is set to com.zaxxer.hikari:name=dataSource,type=HikariDataSource

$> info
#mbean = com.zaxxer.hikari:name=dataSource,type=HikariDataSource
#class name = com.zaxxer.hikari.HikariDataSource
# attributes
  %0 - Connection (javax.sql.Connection, r)
  %1 - HealthCheckRegistry (com.codahale.metrics.health.HealthCheckRegistry, rw)
  ... (메트릭 속성 없음!)
# operations
  %0 - void close()
  %1 - java.sql.Connection getConnection()
  ...
```

**❌ 문제**: `ActiveConnections`, `IdleConnections` 등의 메트릭 속성이 없음!

## 해결: register-mbeans 활성화

### application.properties에 추가

```properties
spring.datasource.hikari.pool-name=RankingHikariPool
spring.datasource.hikari.register-mbeans=true
```

### 다시 실행 후 확인

```
$> beans
#domain = com.zaxxer.hikari:
com.zaxxer.hikari:name=dataSource,type=HikariDataSource
com.zaxxer.hikari:type=Pool (RankingHikariPool)         ← ⭐ 새로 생김!
com.zaxxer.hikari:type=PoolConfig (RankingHikariPool)   ← 설정 정보

$> bean com.zaxxer.hikari:type=Pool (RankingHikariPool)
#bean is set to com.zaxxer.hikari:type=Pool (RankingHikariPool)

$> info
#mbean = com.zaxxer.hikari:type=Pool (RankingHikariPool)
#class name = com.zaxxer.hikari.pool.HikariPool
# attributes
  %0 - ActiveConnections (int, r)           ← ✅ 있음!
  %1 - IdleConnections (int, r)             ← ✅ 있음!
  %2 - ThreadsAwaitingConnection (int, r)   ← ✅ 있음!
  %3 - TotalConnections (int, r)            ← ✅ 있음!
# operations
  %0 - void resumePool()
  %1 - void softEvictConnections()
  %2 - void suspendPool()

$> get ActiveConnections
#mbean = com.zaxxer.hikari:type=Pool (RankingHikariPool):
ActiveConnections = 0;

$> get IdleConnections
#mbean = com.zaxxer.hikari:type=Pool (RankingHikariPool):
IdleConnections = 4;

$> get TotalConnections
#mbean = com.zaxxer.hikari:type=Pool (RankingHikariPool):
TotalConnections = 4;
```

**✅ 해결**: 메트릭 속성이 모두 노출됨!

## 비교

| 설정 | Bean 형식 | 메트릭 노출 | Datadog 수집 |
|------|----------|------------|--------------|
| register-mbeans=false (기본) | `name=dataSource,type=HikariDataSource` | ❌ 없음 | ❌ 불가능 |
| register-mbeans=true | `type=Pool (PoolName)` | ✅ 있음 | ✅ 가능 |

## 추가 테스트

### 부하를 주면서 메트릭 변화 확인

다른 터미널에서:

```bash
# 여러 요청 보내기
for i in {1..10}; do
  curl http://localhost:8081/ranking/user/user1 &
done
```

JMXTerm에서:

```
$> get ActiveConnections
# 값이 증가하는 것 확인

$> get IdleConnections
# 값이 감소하는 것 확인
```

## Kubernetes에서 테스트

Pod에 접속:

```bash
POD_NAME=$(kubectl get pod -l app=ranking-java -o jsonpath='{.items[0].metadata.name}')
kubectl exec -it $POD_NAME -- bash

# JMXTerm 다운로드
curl -L https://github.com/jiaqi/jmxterm/releases/download/v1.0.4/jmxterm-1.0.4-uber.jar -o /tmp/jmxterm.jar

# JMX 접속
java -jar /tmp/jmxterm.jar -l 127.0.0.1:9012

# 위와 동일한 명령어 사용
$> domains
$> domain com.zaxxer.hikari
$> beans
```

## 문제 해결

### JMX 포트에 연결 안 됨

```bash
# 포트 확인
netstat -an | grep 9012

# 프로세스 확인
jps -l
```

### Bean이 보이지 않음

1. `JAVA_TOOL_OPTIONS`가 제대로 설정되었는지 확인
2. 애플리케이션이 완전히 시작될 때까지 대기 (10초 정도)
3. 로그에서 에러 확인

### PostgreSQL 연결 에러

```bash
# Kubernetes에서 port-forward
kubectl port-forward svc/postgres 5432:5432

# 또는 Docker
docker run -d --name postgres \
  -e POSTGRES_DB=app \
  -e POSTGRES_USER=app \
  -e POSTGRES_PASSWORD=app \
  -p 5432:5432 \
  postgres:15
```

## 다음 단계

1. ✅ 로컬에서 Bean 확인 완료
2. ✅ `register-mbeans=true` 효과 확인
3. ⏭️ Kubernetes YAML에 JMX 설정 추가
4. ⏭️ Datadog JMX annotation 설정
5. ⏭️ Datadog에서 메트릭 확인

