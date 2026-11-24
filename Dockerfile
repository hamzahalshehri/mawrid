FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/lib

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/mawrid-server-1.0.jar /app/target/lib/ ./
COPY public ./public
COPY schema.sql ./schema.sql
CMD ["sh","-c","java -cp 'mawrid-server-1.0.jar:*' mawrid.Main"]
