package kappzzang.jeongsan.benchmark;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import kappzzang.jeongsan.domain.Category;
import kappzzang.jeongsan.domain.Expense;
import kappzzang.jeongsan.domain.Item;
import kappzzang.jeongsan.domain.KakaoPayInfo;
import kappzzang.jeongsan.domain.Member;
import kappzzang.jeongsan.domain.PersonalExpense;
import kappzzang.jeongsan.domain.Team;
import kappzzang.jeongsan.domain.TeamMember;
import kappzzang.jeongsan.dto.response.ExpenseResponse;
import kappzzang.jeongsan.global.common.enumeration.Status;
import kappzzang.jeongsan.service.ExpenseService;
import kappzzang.jeongsan.service.ImageStorageService;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비용 목록 조회(ExpenseService.getExpenses) 쿼리 수·응답 시간 측정.
 * <p>
 * - 대상: GET /api/expenses/{teamId}?status=... 가 호출하는 ExpenseService.getExpenses
 * <p>
 * - 쿼리 수: Hibernate Statistics의 prepareStatementCount (요청 1회 동안 JDBC로 나간 SQL 수)
 * <p>
 * - 데이터: 팀 하나에 비용 N건(EXPENSE_BENCH_SIZES), 비용당 항목 K개, 항목마다 다른 팀원 1명의 개인 지출을 둠.
 * 측정 대상 사용자(me)는 비용 index % 4 == 0, 2 → 전 항목 선택(체크 완료), 1 → 첫 항목만 선택, 3 → 선택 없음
 * <p>
 * - 영속성 컨텍스트: 테스트 메서드 트랜잭션을 끄고(NOT_SUPPORTED) 서비스 호출마다 새 트랜잭션에서 실행 → 실제 요청과 같은 조건
 * <p>
 * - 검증: 응답 건수와 PENDING 개인 부담 합계를 시드 데이터로 계산한 기대값과 비교. 불일치가 있으면 결과 파일을 남긴 뒤 실패시킴
 * (쿼리 구조를 바꾼 뒤에도 결과가 같은지 이 벤치마크로 확인할 수 있게 하기 위함)
 * <p>
 * - DB: 기본은 application.properties의 H2(MODE=MYSQL). SPRING_DATASOURCE_URL/USERNAME/PASSWORD 환경변수로 MySQL 지정 가능.
 * ddl-auto=create-drop이므로 반드시 벤치마크 전용 스키마를 사용할 것
 */
@EnabledIfEnvironmentVariable(named = "EXPENSE_BENCH", matches = "(?i)true")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ExpenseService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.root=WARN",
    "logging.level.org.springframework=WARN",
    "logging.level.org.hibernate=WARN",
    "logging.level.kappzzang=WARN"
})
class ExpenseQueryBenchmark {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @MockBean
    private ImageStorageService imageStorageService;

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private TransactionTemplate tx;

    @Test
    void measureGetExpensesQueryCountAndLatency() throws Exception {
        tx = new TransactionTemplate(transactionManager);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        if (!statistics.isStatisticsEnabled()) {
            throw new IllegalStateException("Hibernate statistics가 꺼져 있습니다");
        }

        Config config = Config.fromEnv();
        String database = databaseInfo();
        log("label=%s, DB=%s, 비용 %s건, 비용당 항목 %d개, 팀원 %d명, 카테고리 %d개, %d회차(워밍업 %d회)",
            config.label(), database, config.sizes(), config.itemsPerExpense(), config.members(),
            config.categories(), config.rounds(), config.warmup());

        List<Long> memberIds = seedMembers(config.members());
        List<Long> categoryIds = seedCategories(config.categories());
        List<Fixture> fixtures = new ArrayList<>();
        for (int size : config.sizes()) {
            fixtures.add(seedFixture(size, config.itemsPerExpense(), memberIds, categoryIds));
            log("시드 완료: 비용 %d건 x 2팀(ONGOING/PENDING)", size);
        }

        List<Sample> samples = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            for (Scenario scenario : Scenario.values()) {
                for (int i = 0; i < config.warmup(); i++) {
                    measure(statistics, scenario, fixture, 0);
                }
                for (int round = 1; round <= config.rounds(); round++) {
                    samples.add(measure(statistics, scenario, fixture, round));
                }
                Sample last = samples.get(samples.size() - 1);
                log("N=%-4d %-18s queries=%-5d last=%7.2fms result=%d", fixture.expenseCount(),
                    scenario, last.queries(), last.elapsedMs(), last.resultSize());
            }
        }

        Path outDir = Path.of(config.outDir());
        Files.createDirectories(outDir);
        String stamp = LocalDateTime.now().format(TS);
        String prefix = "expense-query-bench-" + config.label() + "-" + stamp;
        Path csv = outDir.resolve(prefix + ".csv");
        Path summary = outDir.resolve(prefix + "-summary.txt");
        writeCsv(csv, config.label(), samples);
        String summaryText = buildSummary(config, database, samples);
        Files.writeString(summary, summaryText, StandardCharsets.UTF_8);
        System.out.println(summaryText);
        log("raw CSV  : %s", csv.toAbsolutePath());
        log("summary  : %s", summary.toAbsolutePath());

        long invalid = samples.stream().filter(sample -> sample.error() != null).count();
        if (invalid > 0) {
            throw new AssertionError("응답 검증 실패 " + invalid + "건 (상세는 summary 파일의 [검증] 참고)");
        }
    }

    // ===== 측정 =====

    private Sample measure(Statistics statistics, Scenario scenario, Fixture fixture, int round) {
        Long teamId = scenario.status == Status.PENDING ? fixture.pendingTeamId()
            : fixture.ongoingTeamId();
        statistics.clear();
        long start = System.nanoTime();
        ExpenseResponse response = expenseService.getExpenses(fixture.memberId(), teamId,
            scenario.status, scenario.checked);
        double elapsedMs = elapsedMs(start);
        long queries = statistics.getPrepareStatementCount();
        return new Sample(scenario, fixture.expenseCount(), round, queries, elapsedMs,
            response.expenseList().size(), validate(scenario, fixture, response));
    }

    private String validate(Scenario scenario, Fixture fixture, ExpenseResponse response) {
        int expectedSize = switch (scenario) {
            case ONGOING_CHECKED -> fixture.checkedExpenses();
            case ONGOING_UNCHECKED -> fixture.expenseCount() - fixture.checkedExpenses();
            case PENDING -> fixture.expenseCount();
        };
        int actualSize = response.expenseList().size();
        if (actualSize != expectedSize) {
            return "응답 건수 기대 " + expectedSize + " / 실제 " + actualSize;
        }
        if (scenario == Scenario.PENDING && !Objects.equals(response.totalPersonalExpense(),
            fixture.myPersonalSum())) {
            return "개인 부담 합계 기대 " + fixture.myPersonalSum() + " / 실제 "
                + response.totalPersonalExpense();
        }
        return null;
    }

    // ===== 시드 데이터 =====

    private List<Long> seedMembers(int count) {
        if (count < 2) {
            throw new IllegalArgumentException("EXPENSE_BENCH_MEMBERS는 2 이상이어야 합니다");
        }
        return tx.execute(status -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Member member = Member.builder()
                    .kakaoId("bench-kakao-" + i)
                    .email("bench" + i + "@example.com")
                    .nickname("bench-member-" + i)
                    .profileImage("bench")
                    .kakaoPayInfo(new KakaoPayInfo("bench"))
                    .build();
                entityManager.persist(member);
                ids.add(member.getId());
            }
            return ids;
        });
    }

    private List<Long> seedCategories(int count) {
        return tx.execute(status -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Category category = new Category("bench-category-" + i, "#00000" + i);
                entityManager.persist(category);
                ids.add(category.getId());
            }
            return ids;
        });
    }

    private Fixture seedFixture(int expenseCount, int itemsPerExpense, List<Long> memberIds,
        List<Long> categoryIds) {
        return tx.execute(status -> {
            List<Member> members = memberIds.stream()
                .map(id -> entityManager.find(Member.class, id))
                .toList();
            List<Category> categories = categoryIds.stream()
                .map(id -> entityManager.find(Category.class, id))
                .toList();

            Team ongoingTeam = createTeam(members, "ongoing-" + expenseCount);
            Team pendingTeam = createTeam(members, "pending-" + expenseCount);
            SeedResult ongoing = seedExpenses(ongoingTeam, members, categories, expenseCount,
                itemsPerExpense, false);
            SeedResult pending = seedExpenses(pendingTeam, members, categories, expenseCount,
                itemsPerExpense, true);
            if (ongoing.checkedExpenses() != pending.checkedExpenses()) {
                throw new IllegalStateException("두 팀의 시드 패턴이 다릅니다");
            }
            return new Fixture(expenseCount, ongoingTeam.getId(), pendingTeam.getId(),
                members.get(0).getId(), ongoing.checkedExpenses(), pending.myPersonalSum());
        });
    }

    private Team createTeam(List<Member> members, String name) {
        Team team = Team.createTeam(members.get(0), name, "bench",
            members.subList(1, members.size()));
        entityManager.persist(team);
        for (TeamMember teamMember : team.getTeamMemberList()) {
            if (!teamMember.getIsInviteAccepted()) {
                teamMember.acceptInvite();
            }
        }
        return team;
    }

    private SeedResult seedExpenses(Team team, List<Member> members, List<Category> categories,
        int expenseCount, int itemsPerExpense, boolean pending) {
        Member me = members.get(0);
        int checkedExpenses = 0;
        int myPersonalSum = 0;
        for (int i = 0; i < expenseCount; i++) {
            Member payer = members.get(i % members.size());
            List<Item> items = new ArrayList<>();
            for (int k = 0; k < itemsPerExpense; k++) {
                items.add(Item.builder()
                    .name("item-" + k)
                    .quantity(k % 3 + 1)
                    .unitPrice(1_000 * (k + 1))
                    .build());
            }
            Expense expense = Expense.builder()
                .team(team)
                .member(payer)
                .category(categories.get(i % categories.size()))
                .title("expense-" + i)
                .imageUrl("bench")
                .paymentTime(LocalDateTime.now().minusMinutes(i))
                .items(items)
                .build();
            entityManager.persist(expense);

            // me의 선택 패턴: 0,2 → 전 항목 / 1 → 첫 항목만 / 3 → 없음
            int pattern = i % 4;
            if (pattern == 0 || pattern == 2) {
                checkedExpenses++;
            }
            for (int k = 0; k < items.size(); k++) {
                Item item = items.get(k);
                boolean meConsumes = pattern == 0 || pattern == 2 || (pattern == 1 && k == 0);
                if (meConsumes) {
                    persistPersonalExpense(me, item, item.getQuantity(), item.getTotalPrice());
                    myPersonalSum += item.getTotalPrice();
                }
                Member other = members.get(1 + (i + k) % (members.size() - 1));
                persistPersonalExpense(other, item, 1, item.getUnitPrice());
            }

            if (pending) {
                expense.changeStatus(team.getId(), payer.getId(), Status.PENDING);
            }
        }
        return new SeedResult(checkedExpenses, myPersonalSum);
    }

    private void persistPersonalExpense(Member member, Item item, int quantity, int totalPrice) {
        entityManager.persist(PersonalExpense.builder()
            .member(member)
            .item(item)
            .quantity(quantity)
            .totalPrice(totalPrice)
            .build());
    }

    // ===== 출력 =====

    private void writeCsv(Path path, String label, List<Sample> samples) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path,
            StandardCharsets.UTF_8))) {
            writer.print('﻿');
            writer.println("label,scenario,expenses,round,queries,elapsed_ms,result_size,error");
            for (Sample sample : samples) {
                writer.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%.3f,%d,%s%n", label, sample.scenario(),
                    sample.expenses(), sample.round(), sample.queries(), sample.elapsedMs(),
                    sample.resultSize(), sample.error() == null ? "" : sample.error());
            }
        }
    }

    private String buildSummary(Config config, String database, List<Sample> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== 비용 목록 조회(ExpenseService.getExpenses) 쿼리 수·응답 시간 측정 결과 ====\n");
        sb.append(String.format(Locale.ROOT, "label: %s / DB: %s%n", config.label(), database));
        sb.append(String.format(Locale.ROOT,
            "비용 %s건 x 시나리오 %d개 x %d회차 (워밍업 %d회 제외), 비용당 항목 %d개, 팀원 %d명, 카테고리 %d개%n%n",
            config.sizes(), Scenario.values().length, config.rounds(), config.warmup(),
            config.itemsPerExpense(), config.members(), config.categories()));

        List<Sample> invalid = samples.stream().filter(sample -> sample.error() != null).toList();
        sb.append("[검증] ").append(invalid.isEmpty() ? "PASS" : "FAIL").append('\n');
        sb.append("  응답 건수·PENDING 개인 부담 합계를 시드 기대값과 비교, 불일치 ")
            .append(invalid.size()).append("건\n");
        invalid.stream().limit(10).forEach(sample -> sb.append(String.format(Locale.ROOT,
            "  - N=%d %s round %d: %s%n", sample.expenses(), sample.scenario(), sample.round(),
            sample.error())));
        sb.append('\n');

        Map<String, List<Sample>> grouped = samples.stream().collect(Collectors.groupingBy(
            sample -> sample.scenario() + "|" + sample.expenses(), LinkedHashMap::new,
            Collectors.toList()));

        sb.append("[요청 1회당 쿼리 수]\n");
        sb.append(String.format(Locale.ROOT, "  %-18s", "scenario"));
        config.sizes().forEach(size -> sb.append(String.format(Locale.ROOT, "%9s", "N=" + size)));
        sb.append('\n');
        List<String> unstable = new ArrayList<>();
        for (Scenario scenario : Scenario.values()) {
            sb.append(String.format(Locale.ROOT, "  %-18s", scenario));
            for (int size : config.sizes()) {
                List<Sample> group = grouped.getOrDefault(scenario + "|" + size, List.of());
                long min = group.stream().mapToLong(Sample::queries).min().orElse(0);
                long max = group.stream().mapToLong(Sample::queries).max().orElse(0);
                sb.append(String.format(Locale.ROOT, "%9d", max));
                if (min != max) {
                    unstable.add(scenario + " N=" + size + " (" + min + "~" + max + ")");
                }
            }
            sb.append('\n');
        }
        sb.append(unstable.isEmpty() ? "  * 모든 조합에서 회차별 쿼리 수 동일\n"
            : "  * 회차별 쿼리 수가 달랐던 조합(최댓값 표기): " + String.join(", ", unstable) + "\n");
        sb.append('\n');

        sb.append("[응답 시간 (ms)]\n");
        sb.append(String.format(Locale.ROOT, "  %-18s %6s %5s %9s %9s %9s %9s %9s %9s %9s %9s%n",
            "scenario", "N", "n", "mean", "std", "min", "p50", "p90", "p95", "p99", "max"));
        for (Scenario scenario : Scenario.values()) {
            for (int size : config.sizes()) {
                double[] values = grouped.getOrDefault(scenario + "|" + size, List.of()).stream()
                    .mapToDouble(Sample::elapsedMs).sorted().toArray();
                if (values.length == 0) {
                    continue;
                }
                double mean = Arrays.stream(values).average().orElse(0);
                double std = Math.sqrt(Arrays.stream(values)
                    .map(value -> (value - mean) * (value - mean)).average().orElse(0));
                sb.append(String.format(Locale.ROOT,
                    "  %-18s %6d %5d %9.2f %9.2f %9.2f %9.2f %9.2f %9.2f %9.2f %9.2f%n", scenario,
                    size, values.length, mean, std, values[0], percentile(values, 50),
                    percentile(values, 90), percentile(values, 95), percentile(values, 99),
                    values[values.length - 1]));
            }
        }
        sb.append("  * 서비스 메서드 호출 구간(트랜잭션 시작~종료) 기준. HTTP·직렬화 시간은 제외\n");
        return sb.toString();
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = p / 100.0 * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower);
    }

    private String databaseInfo() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static void log(String format, Object... args) {
        System.out.printf("[expense-bench] " + format + "%n", args);
    }

    // ===== 타입 =====

    private enum Scenario {
        ONGOING_CHECKED(Status.ONGOING, true),
        ONGOING_UNCHECKED(Status.ONGOING, false),
        PENDING(Status.PENDING, true);

        private final Status status;
        private final Boolean checked;

        Scenario(Status status, Boolean checked) {
            this.status = status;
            this.checked = checked;
        }
    }

    private record Config(String label, List<Integer> sizes, int itemsPerExpense, int members,
                          int categories, int rounds, int warmup, String outDir) {

        static Config fromEnv() {
            List<Integer> sizes = Arrays.stream(
                    env("EXPENSE_BENCH_SIZES", "10,50,100,200").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .toList();
            return new Config(
                env("EXPENSE_BENCH_LABEL", "baseline"),
                sizes,
                Integer.parseInt(env("EXPENSE_BENCH_ITEMS", "5")),
                Integer.parseInt(env("EXPENSE_BENCH_MEMBERS", "4")),
                Integer.parseInt(env("EXPENSE_BENCH_CATEGORIES", "3")),
                Integer.parseInt(env("EXPENSE_BENCH_ROUNDS", "30")),
                Integer.parseInt(env("EXPENSE_BENCH_WARMUP", "5")),
                env("EXPENSE_BENCH_OUT", "benchmark/results"));
        }

        private static String env(String name, String defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? defaultValue : value;
        }
    }

    private record Fixture(int expenseCount, Long ongoingTeamId, Long pendingTeamId,
                           Long memberId, int checkedExpenses, int myPersonalSum) {

    }

    private record SeedResult(int checkedExpenses, int myPersonalSum) {

    }

    private record Sample(Scenario scenario, int expenses, int round, long queries,
                          double elapsedMs, int resultSize, String error) {

    }
}
