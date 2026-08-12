# MAT 3470 — TI-84 combinatorics reference

A TI-BASIC program (`MAT3470`) that pages through the formulas and "when do I
use this" cues for MAT 3470 Exam II, plus a calculator section that computes the
awkward ones (onto functions, derangements, distribution numbers).

Built from three sources so the notation matches the course exactly: the
August 5, 2024 Exam II and its key, the professor's scope notes, and Marcus,
*Combinatorics: A Problem Oriented Approach* (MAA, 1998).

> **Before you use this:** the exam paper states "NO NOTES, BOOKS, CELL PHONE
> NOR INTERNET IS ALLOWED. CALCULATORS ARE ALLOWED." A formula reference on a
> permitted calculator is the usual way around a no-notes rule. Check with your
> instructor whether calculator programs are allowed before a graded sitting.
> Nothing here is disguised or hidden from a memory check.

## Scope

Per the professor's review notes:

| | |
|---|---|
| **Section C, pp. 31–40** | distributions, distribution/multinomial numbers, T-numbers |
| **omit pp. 41–43** | the Flagpole Problem — confirmed, that subsection starts on p.41 |
| **Section D** | skipped entirely |
| **Section E** | inclusion & exclusion — *review E3, E4, E5* |
| **Section F** | recurrence relations |
| **Section G** | generating functions — the coin problem |

Sections A and B were Exam I; the exam is not cumulative. Section H
(Pólya–Redfield) was not listed.

Out-of-scope material is **kept but demoted** — it lives under `OFF SCOPE *`
on the main menu, and every page there is marked with `*`. Nothing was deleted.

## Getting it onto the calculator

The file is plain TI-BASIC source, not a `.8xp` binary. Three ways in:

1. **TI Connect CE** — Program Editor → New Program → name it `MAT3470` → paste
   the contents of `MAT3470.txt` → Send to calculator.
2. **SourceCoder 3** (cemetech.net/sc) — import the `.txt`, export as `.8xp`,
   send with TI Connect CE.
3. **Type it in** — `PRGM` → `NEW` → `MAT3470`. Slow but works.

Notes on the source text:

- `→` is the STO▸ key. If your import tool wants ASCII, `->` also works.
- `nCr` and `nPr` are the `MATH` → `PRB` → `3` and `2` tokens, not three
  letters typed out.
- Runs on the TI-84 Plus and TI-84 Plus CE. Screens are laid out for the
  16-column monochrome home screen, so they just look roomy on a CE.

Run it with `PRGM` → `MAT3470` → `ENTER`. `ENTER` advances each page.

## Menu map

```
MAT 3470 EXAM2
 1 C DISTRIBUTNS   balls+boxes / T-triangle / multinomial      (C p31-40)
 2 E INCL-EXCL     the method / E3 E4 E5 / worked exam examples
 3 F RECURRENCE    characteristic-equation method
 4 G COIN/GENFN    generating functions, the coin problem
 5 STD PROBS 8-14  Marcus's Standard Problems for Section C
 6 CALCULATE       computes things
 7 OFF SCOPE *     A, B, flagpole, D, H — kept for background

CALCULATE
 1 C AND P           nCr and nPr together
 2 C REPEATS (R)     (n choose k)_R, and the no-missing variant
 3 T(M,N) ONTO       onto functions, via the alternating sum
 4 MULTINOMIAL       enter the part sizes, get M!/(m₁!m₂!…)
 5 DERANGEMENT       Dₙ, via the same inclusion/exclusion the key uses
 6 BALLS+BOXES       asks distinct/identical and empty/no-empty, then answers
 7 BACK / QUIT
```

## The cheat sheet itself

### The balls-and-boxes table — Section C

`m` balls into `n` boxes, boxes always distinct (identical boxes is Section D).

| | empty boxes allowed | no box empty |
|---|---|---|
| **distinct balls** | `nᵐ` (#8) | `T(m,n)` (#13) |
| **identical balls** | `C(m+n−1, m)` = `(n choose m)_R` (#9) | `C(m−1, n−1)` (#10) |

Fixed box sizes m₁…m_n with distinct balls: `m!/(m₁!·m₂!·…·m_n!)` — the
distribution number (#11), same formula as word rearrangements (#12).

From the exam: 6 distinct balls into 5 distinct boxes, none empty, is
`T(6,5) = 1800`. Six *identical* balls into 5 distinct boxes is
`(5 choose 6)_R = C(10,6) = 210`.

### T-numbers (onto functions)

`T(m,n)` = onto functions from an m-set to an n-set = m distinct balls into n
distinct boxes, no box empty. Marcus defines it as the sum of all distribution
numbers with every `mᵢ ≥ 1`.

```
T(m,n) = n · [ T(m−1,n−1) + T(m−1,n) ]     for 1 < n < m
T(m,1) = 1,   T(m,m) = m!

row 1   1
row 2   1    2
row 3   1    6    6
row 4   1   14   36    24
row 5   1   30  150   240   120
row 6   1   62  540  1560  1800   720
```

Closed form: `T(m,n) = Σᵢ (−1)ⁱ C(n,i) (n−i)ᵐ` — this is just E3's argument
(inclusion/exclusion on which boxes are empty) written out.

### Inclusion / exclusion — Section E

Name the *bad* events A, B, C…. Then `S₀ = |U|`, `S₁ = Σ|A|`, `S₂ = Σ|AB|`, and
`S_k` = the sum over all k-way overlaps.

```
none of them:     |A′B′C′…| = S₀ − S₁ + S₂ − S₃ + …
at least one:     S₁ − S₂ + S₃ − …  =  S₀ − (none)
```

**The three problems the professor flagged**, which are the three archetypes:

- **E3 — onto words.** Five letters from a 3-letter set. Total 3⁵ = 243. A = "first
  letter missing", so |A|=|B|=|C|=2⁵=32, each pair-intersection = 1, triple = 0.
  Union = 96 − 3 = 93 have a letter missing, so 243 − 93 = **150 = T(5,3)**.
- **E4 — derangements.** Rearrangements of 1234 with at least one digit in place:
  S₁ = C(4,1)3! = 24, S₂ = C(4,2)2! = 12, S₃ = 4, S₄ = 1 → 24−12+4−1 = **15**,
  so D₄ = 24 − 15 = **9**.
- **E5 — divisibility.** 1…60 divisible by 2, 3 or 5: 30+20+12 − (10+6+4) + 2 =
  **44**. Relatively prime to 60: 60 − 44 = **16** (that's Euler's φ(60)).

Worked exam examples also in the program:

- 1…210 divisible by 2, 3, 5 or 7 → 210 − 48 = **162**.
- `11223344` with no two adjacent equal: S₀ = 8!/2⁴ = 2520, gluing j pairs gives
  `(8−j)!/2^(4−j)` → 2520−2520+1080−240+24 = **864**.
- 5 red + 4 blue into 3 boxes, none empty: 315 − 90 + 3 − 0 = **228**.
- Derangement values: D₂=1, D₃=2, D₄=9, D₅=44, D₆=265, D₇=1854. Shortcut
  `Dₙ = round(n!/e)`.

### Distribution numbers and the trinomial theorem — Section C

```
(m; m₁,…,m_n) = m! / (m₁!·…·m_n!),   m₁+…+m_n = m

(a+b+c)^m = Σ (m; m₁,m₂,m₃) a^m₁ b^m₂ c^m₃    over all mᵢ ≥ 0, Σmᵢ = m
```

The exam's three plug-in tricks:

- `a = b = c = 1` → the sum of all trinomial coefficients is `3^m`.
- `a + b + c = 1` (e.g. ½ + ⅙ + ⅓) → the weighted sum is `1^m = 1`.
- every `mᵢ ≥ 1` → the sum is `T(m,n)`. For m = 6, n = 3: **540**.

Number of terms in `(a+b+c)^m` is `C(m+2, 2)`.

Coefficient extraction: in `(x + 2y + 3z)^10`, the `x²y³z⁵` coefficient is
`(10; 2,3,5)·2³·3⁵ = 2520 · 8 · 243 = 4,898,880`.

> The scanned key reads `489880`, which is one digit short — 2520·1944 is
> 4,898,880. Worth confirming with your instructor, but the arithmetic is
> unambiguous.

### Generating functions — Section G

One factor per item type; the exponent is how many of that item you use;
multiply out and read the coefficient of `x^target`.

```
any amount    1 + x + x² + …        = 1/(1−x)
at most 3     1 + x + x² + x³
at least 1    x + x² + x³ + …
```

The coin problem: 3 quarters, 6 dimes, 10 nickels making 100¢ →

```
(1 + x²⁵ + x⁵⁰ + x⁷⁵)(1 + x¹⁰ + … + x⁶⁰)(1 + x⁵ + … + x⁵⁰)
```

coefficient of `x¹⁰⁰` = **15**.

### Recurrence relations — Section F

For `x_{n+2} = a·x_{n+1} + b·x_n`:

1. Characteristic equation `r² = a·r + b`, i.e. `r² − a·r − b = 0`.
2. Solve for roots r₁, r₂ (the course calls them eigenvalues).
3. `x_n = A·r₁ⁿ + B·r₂ⁿ`. If r₁ = r₂ = r, use `x_n = (A + B·n)·rⁿ`.
4. Plug in the two known terms, solve for A and B.

Exam problem: `x_{n+2} = x_{n+1} + 2x_n`, x₁ = 1, x₂ = 3 → `r² − r − 2 = 0` →
r = 2, −1 → `x_n = ⅔·2ⁿ + ⅓·(−1)ⁿ`, so `x₃₀ = (2³¹ + 1)/3 = 715,827,883`.

## Off-scope material (kept, marked `*`)

Reachable from menu item 7, all clearly flagged:

- **A Strings** — `nᵏ`, `n!/(n−k)!`, product rule, derangement numbers (SP #1, #2)
- **B Combinations** — `C(n,k)`, Pascal, bit strings: `C(m+n,n)`, no two adjacent
  1s `C(m+1,n)`, 0-dominated `C(m+n,n) − C(m+n,n−1)` (SP #3–#7)
- **C Flagpole** (pp.41–43) — m distinct flags on n distinct poles, order on a
  pole matters: `n(n+1)…(n+m−1) = (n+m−1)!/(n−1)!`
- **D Partitions** — Stirling `S(m,n) = T(m,n)/n!` with
  `S(m,n) = S(m−1,n−1) + n·S(m−1,n)`; numerical partitions
  `P(m,n) = P(m−1,n−1) + P(m−n,n)`; `P_k(m,n) = P(m−(k−1)n, n)`;
  `P*(m,n) = P(m−C(n,2), n)`
- **H Pólya–Redfield** — cycle codes → cycle index (average of codes) → cycle
  polynomial (all Xᵢ = X, then X = k) → pattern inventory (Xᵢ = A₁ⁱ+…+A_kⁱ)

## Limits worth knowing

- `!` overflows past 69! on a TI-84, so the multinomial calculator caps out at
  a total of 69. `nCr` is computed without full factorials and goes much higher.
- `nCr` with k > n returns 0 rather than erroring.
- T(m,n) and Dₙ use the alternating sums above, so they match the key's method
  rather than a lookup table.
- The program uses real variables `M N K I T D W G S` — it will overwrite
  whatever you had in those.
