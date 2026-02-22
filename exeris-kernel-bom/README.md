# Exeris Kernel BOM

**Module:** `eu.exeris:exeris-kernel-bom`  
**Role:** Central Dependency Registry

## Overview
The Bill of Materials (BOM) is the single source of truth for all dependency versions within the Exeris Kernel ecosystem. It ensures that all modules use compatible versions of high-performance libraries like Agrona, JCTools, and Jackson 3.

## 🛠️ Key Stack
- **Foundation:** Agrona (Zero-allocation structures), JCTools (MPSC queues).
- **Drivers:** Jackson 3 (Core-only), HikariCP 7, PostgreSQL 42.7+.
- **Observability:** SLF4J 2.0+, Logback 1.5+.

## Usage
Import this BOM in your `dependencyManagement` section to inherit validated versions:
```xml
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-bom</artifactId>
    <version>${project.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```