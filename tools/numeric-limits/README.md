# tools/numeric-limits - measurements behind `NumericLimits`

`NumericLimitsBenchmark.java` is a standalone program (default package). It prints two Markdown tables:

1. the raw `BigInteger` cost of a rational sum `p/q + r/s` for component sizes from 2048 to 32768 bits (three products,
   then a `gcd` of `2N`-bit numbers, which dominates): this is what chose `MAX_COMPONENT_BITS`;
2. the slowest operations and the refusals of `calc.number` at the configured limits, with the duration of the first
   call in a fresh JVM and the best of 30 calls.

It is not part of the production code and is not built by Gradle. The figures are quoted in
`docs/adr/0003-representation-numerique.md`; `NumericLimitsTest` keeps the 10 ms budget as a permanent test.

## Run

From the repository root, in PowerShell, with a JDK 21 (`.\gradlew.bat -q printToolchainHome` prints the one Gradle uses):

```powershell
$jdk = "$(.\gradlew.bat -q printToolchainHome)\bin"
$out = Join-Path $env:TEMP "calc-numeric-limits"
& "$jdk\javac.exe" -encoding UTF-8 -d $out (Get-ChildItem src\main\java\calc\number -Filter *.java | ForEach-Object FullName) tools\numeric-limits\NumericLimitsBenchmark.java
& "$jdk\java.exe" -cp $out NumericLimitsBenchmark
```

The inputs come from a fixed seed (20261026), so two runs measure the same numbers; the durations vary by a few percent
from one run to the next.
