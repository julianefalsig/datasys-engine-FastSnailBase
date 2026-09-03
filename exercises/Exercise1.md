Exercise 1: Team, Toolchain, and the First Green Pull Request

How to Build Data Systems – Fall 2026

In this exercise, your team sets up the engineering environment for the entire semester: a shared GitHub repository with a protected main branch, a Maven build whose tests run in CI on every pull request, a logging library that writes CSV log lines, and a first tagged release. Every later exercise builds directly on this repository.

Prerequisites: a GitHub account for every team member, Java 25 or newer (java -version), Apache Maven 3.9 or newer (mvn -version), Git, and an IDE (VSCode recommended).

1. Form a team and create the repository

Form a team of 2–3 students.
Create a repository and name it datasys-engine-<teamname>.
Add all team members and your TA, Alperen Aydin (GitHub username alqeren1), as collaborators.

Note: Branch protection (Task 7) requires a public repository or a paid GitHub plan. Either make the repository public, or claim the free GitHub Student Developer Pack, which also gives you more AI via GitHub Copilot Pro.

2. Set up the Maven project

Create this layout:

datasys-engine-<teamname>/
├── pom.xml
├── .gitignore                      (must ignore target/ and logs/)
├── src/main/java/dk/itu/datasys/Engine.java
├── src/main/resources/log4j2.xml   (Task 3)
└── src/test/java/dk/itu/datasys/EngineTest.java

Use this pom.xml as your starting point (replace dk.itu.datasys with your own package if you prefer):

<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>dk.itu.datasys</groupId>
  <artifactId>engine</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <log4j.version>2.26.1</log4j.version>
  </properties>

  <dependencies>
    <!-- Logging API: your code compiles only against SLF4J -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
      <version>2.0.18</version>
    </dependency>
    <!-- Logging backend: Log4j2, needed at runtime only -->
    <dependency>
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-slf4j2-impl</artifactId>
      <version>${log4j.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-core</artifactId>
      <version>${log4j.version}</version>
      <scope>runtime</scope>
    </dependency>
    <!-- Testing -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>6.1.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Compiles with the Java release set above. Pinned because the version
           Maven picks by default is too old to understand `maven.compiler.release`. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.15.0</version>
      </plugin>
      <!-- Runs the JUnit tests during `mvn test` / `mvn package` / `mvn verify` -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.6</version>
      </plugin>
      <!-- Lets `mvn exec:java` start the engine -->
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.6.3</version>
        <configuration>
          <mainClass>dk.itu.datasys.Engine</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>

The Maven commands you will use all semester:

Command	What it does
mvn clean package	Compiles, runs all tests, and produces target/engine-0.1.0.jar
mvn test	Compiles and runs the tests only
mvn -B verify	What CI runs: like package, non-interactive output
mvn compile	Compiles the source only
mvn exec:java	Runs the already-compiled main class; fails if nothing has been compiled yet
	Note: Maven has no built-in mvn start (that is npm's convention). The standard way to run a plain Java project is the exec-maven-plugin configured above. Run mvn compile and mvn exec:java as two commands, or chained as mvn compile exec:java.

Requirement: mvn compile exec:java, with no further arguments, must print your team name.

Engine.java starting point:

package dk.itu.datasys;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");
        System.out.println(new Engine().teamName());
        LOGGER.debug("engine stopped");
    }

    String teamName() {
        return "Team <your team name>";
    }
}

The team name sits in its own teamName() method rather than inline in the println: a method that returns a value can be unit-tested, a println buried in main cannot. Task 4 tests exactly this method.

3. Add logging

Your engine will later analyze its own logs with its own COPY and SELECT commands. That only works if the log is CSV in a schema the engine can load. The course's suggested schema:

timestamp, sessionId, statementNumber, threadId, logLevel, className, logMessage

Three of these columns identify which run, which statement, and which class a line belongs to:

sessionId (STRING) identifies one run of the engine. Generate a random string once at startup, with UUID.randomUUID().toString() as in the code below, and put it in the MDC. From week 4 on, one session is one SQL script that the engine executes.
statementNumber (LONG) counts the SQL statements within a session: the first statement of the script is 1, the second is 2, and so on. Your engine executes no SQL yet, so set it to 0 at startup and leave it there this week. Zero means "this line does not belong to a statement". That keeps the column a valid integer on every line, which matters in week 5: you will COPY this file into a LONG column, and an empty field would fail to parse.
className (STRING) names the class that wrote the line. It costs you nothing: every logger is created for a class (LoggerFactory.getLogger(Engine.class)), and %logger{1} in the PatternLayout writes that class's simple name into every line. As the engine grows components, this column is what isolates one component's lines with a plain equality filter.

Fill the first two via the MDC, once at startup, with %X{sessionId} and %X{statementNumber} as their own fields in the PatternLayout; the third comes from the logger itself. Every log line the engine writes afterwards then carries all three automatically.

The dependencies in Task 2 give you SLF4J (the logging API your code uses) with Log4j2 (the backend that formats and writes the file). Configure the backend in src/main/resources/log4j2.xml:

<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="warn">
  <Appenders>
    <!-- One live log file; full files rotate away as engine-1.log, engine-2.log, ... -->
    <RollingFile name="CsvLog" fileName="logs/engine.log"
                 filePattern="logs/engine-%i.log">
      <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS},%X{sessionId},%X{statementNumber},%tid,%level,%logger{1},%m%n"/>
      <SizeBasedTriggeringPolicy size="10 MB"/>
      <DefaultRolloverStrategy max="1000"/>
    </RollingFile>
    <!-- Console copy for debugging; stderr keeps stdout clean for program output -->
    <Console name="Console" target="SYSTEM_ERR">
      <PatternLayout pattern="%d{HH:mm:ss.SSS} %level %logger{1} - %m%n"/>
    </Console>
  </Appenders>
  <Loggers>
    <Root level="debug">
      <AppenderRef ref="CsvLog"/>
      <AppenderRef ref="Console"/>
    </Root>
  </Loggers>
</Configuration>

How the pattern maps to the schema: %d{…} is the timestamp; %X{sessionId} and %X{statementNumber} read the SLF4J MDC (per-thread context); %tid is the numeric thread id; %level and %m are the log level and message; %logger{1} is the simple name of the class the logger was created for. An MDC key that was never set renders as an empty field. That is fine for a STRING column, but not for statementNumber, which is why you set it to 0 at startup.

All runs write to the one active file logs/engine.log, so it always contains the newest lines; each new run appends, and sessionId separates the runs. When the file reaches the size threshold, its content moves to engine-1.log, engine-2.log, and so on, with higher numbers newer. You will not reach 10 MB for weeks. To see one rollover now, lower the threshold to 1 KB, start the engine a few times, and restore the threshold.

Log levels: the course uses exactly two log levels. LOGGER.debug records normal work; LOGGER.error records failures. Nothing can fail in this week's engine, so every line is debug for now. Once the engine can fail, every exception it throws must leave at least one ERROR line in the log.

Requirement: after one mvn compile exec:java run, logs/engine.log exists and contains at least one well-formed log line.

4. Add a first unit test

package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class EngineTest {
    @Test
    void teamName() {
        assertEquals("Team <your team name>", new Engine().teamName());
    }
}

One test class per class under test, named after it: Engine.java gets EngineTest.java. The test is small, but it is a real test and it proves the test toolchain and CI work end to end.

5. Set up CI with GitHub Actions

Create .github/workflows/ci.yml:

name: CI
on:
  pull_request:
  push:
    branches: [main]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'
          cache: maven
      - run: mvn -B verify

Requirement: the workflow runs on every pull request and on pushes to main, and shows up as a check on PRs.

6. Add the pull request template

Create .github/pull_request_template.md:

## What and why


## How was it tested?


## AI attribution

- Name the AI tools that contributed to this change.

7. Protect the main branch

Do Tasks 1–6 as direct pushes to main; then turn on protection. In the repository, go to Settings → Branches → Add branch protection rule (or a Ruleset) for main:

Require a pull request before merging, with at least 1 approving review.
Require status checks to pass: select the CI job (build). The check only appears in this list after it has run at least once, so push something that triggers CI first.
Block force pushes; apply the rules to administrators too.

Requirement: verify the protection works. A direct git push origin main must be rejected.

8. Demonstrate the full loop

Run one complete change through the process, from ticket to merged code:

Open a GitHub issue describing the change, for example "Find a better team name". Note its number; we assume #1 below. Issues are the course's ticket system; the same idea appears in industry as JIRA tickets, Linear issues, or Azure Boards work items.
Create a branch (e.g., fix-team-name).
Make the change, and reference the issue in the commit message, e.g. Better team name (#1). GitHub links the commit to the issue automatically.
Push the branch and open a pull request; fill in the template, and write Fixes #1 in the description so the merge closes the issue.
A teammate reviews on GitHub and leaves at least one substantive comment or question; the author responds or updates the code.
Once the review is approved and CI is green, click the "Merge pull request" button on the PR page. An approved review and a green check do not merge the PR by themselves; someone has to press the button. The button offers three strategies: merge commit, squash, or rebase. The default merge commit is fine for this course; many industry teams squash so that one PR becomes one commit. Then delete the branch, and check that issue #1 closed itself.

9. Cut release v0.1

git checkout main && git pull
git tag -a v0.1 -m "Exercise 1: toolchain baseline"
git push origin v0.1

This creates a tag that the teaching assistant will check out to validate the state of your project.

Definition of done

Repository exists; all team members and the TA have access.
mvn clean package succeeds locally on every member's machine.
mvn compile exec:java prints the team name.
A run produces logs/engine.log with at least one well-formed CSV log line and no header line.
The teamName unit test runs in CI on every PR.
main is protected: PR + 1 approving review + green CI required; direct pushes rejected.
The PR template is in place.
At least one merged PR shows a real review conversation and closed a GitHub issue via Fixes #N.
Tag v0.1 is pushed.
