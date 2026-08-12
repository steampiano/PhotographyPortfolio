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
 1 C DISTRIBUTNS   balls+boxes / T-triangle / multinomial / std probs 8-14
 2 E INCL-EXCL     the method / E3 E4 E5 / worked exam examples
 3 F RECURRENCE    characteristic-equation method
 4 G COIN/GENFN    generating functions, the coin problem
 5 EXAM I REVIEW   A strings / B combinations / paths+ballot / answers
 6 CALCULATE       computes things
 7 OFF SCOPE *     flagpole, D, H, std probs 1-7 — kept for background

CALCULATE
 1 C AND P           nCr and nPr together
 2 C REPEATS (R)     (n choose k)_R, and the no-missing variant
 3 T(M,N) ONTO       onto functions, via the alternating sum
 4 MULTINOMIAL       enter the part sizes, get M!/(m₁!m₂!…)
 5 DERANGEMENT       Dₙ, via the same inclusion/exclusion the key uses
 6 BALLS+BOXES       asks distinct/identical and empty/no-empty, then answers
 7 BACK / QUIT
```

## Sample exam → where the formula lives

The Aug 5 2024 Exam II doubles as the sample final. Every problem on it is
covered; this is the lookup path for each.

| Q | What it asks | § | Menu path |
|---|---|---|---|
| 1(A) | first six rows of the T-triangle | C | `1 → T-TRIANGLE` (rows pages) |
| 1(B) | 6 distinct balls → 5 distinct boxes, none empty | C | `1 → BALLS+BOXES` · calc `3` → **1800** |
| 1(C) | 6 identical balls → 5 distinct boxes | C | `1 → BALLS+BOXES` · calc `6` → **210** |
| 2 | 1…210 divisible by 2, 3, 5 or 7 | E | `2 → EXAM EXAMPLES` → **162** |
| 3(A) | trinomial sums (=1, =3ᵐ, all mᵢ≥1) | C | `1 → MULTINOMIAL`, plug-in tricks → **540** |
| 3(B) | coefficient of x²y³z⁵ in (x+2y+3z)¹⁰ | C | `1 → MULTINOMIAL`, find a coeff |
| 4 | 5 red + 4 blue into 3 boxes, none empty | C+E | `2 → EXAM EXAMPLES` → **228** |
| 5 | coins making one dollar | G | `4 → COIN PROBLEM` → **15** |
| 6 | recurrence, then x₃₀ | F | `3 → F RECURRENCE` → **715,827,883** |
| 7(A) | derangements of 1,2,3,4,5 | E | `2 → E3 E4 E5` · calc `5` → **44** |
| 7(B) | 11223344, no two consecutive equal | E | `2 → EXAM EXAMPLES` → **864** |
| 8(A) | 6 people, 3 exams, free | C | `1 → BALLS+BOXES` → **3⁶** |
| 8(B) | every exam taken by somebody | C | calc `3` → **T(6,3) = 540** |
| 8(C) | split 2/2/2 and 3/2/1 | C | calc `4` → **90**, **60** |
| 8(D) | redo (A),(B) with identical people | C | calc `6` → **28**, **10** |

Note the shape of it: five of the eight problems are Section C, and the two
Section E problems both run through inclusion/exclusion on "bad" events. If
you're short on time, `1 → BALLS+BOXES` and `2 → THE METHOD` carry the most
weight.

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

## Exam I review — menu 5

Sections A and B. Not on this exam (it isn't cumulative), but on its own menu
rather than buried, built from the July 8 2024 Exam I paper.

**Strings (A)**

```
length k from an n-set          nᵏ
no element repeated             n!/(n−k)!
product rule                    n₁·n₂·…·n_k
exactly r copies of one letter  C(k,r)·(n−1)^(k−r)
exactly j different letters     C(n,j)·T(k,j)
```

That last one is worth noticing: **Exam I's "exactly two different letters"
problem is a T-number in disguise.** Five letters from {A,B,C} using exactly
two: `C(3,2)·T(5,2) = 3·30 = 90`, and `T(5,2) = 2⁵−2 = 30`. Same machinery as
Section C — the two exams are closer than they look.

**Combinations (B)**

```
C(n,k) = n!/(k!(n−k)!)          Pascal: C(n,k)+C(n,k+1) = C(n+1,k+1)
binomial theorem                (a+b)ⁿ = Σ C(n,k) a^(n−k) b^k
  Σ C(n,k) = 2ⁿ                 alternating sum = 0      evens = 2^(n−1)
hockey stick                    C(2,2)+…+C(n,2) = C(n+1,3)
m 0s and n 1s                   C(m+n,n)
  …no two consecutive 1s        C(m+1,n)          (gaps method)
combinations with repetition    (n choose k)_R = C(n+k−1,k)
  …at least one of each         C(k−1,n−1)
```

**Paths and the ballot problem**

Grid paths with only right/down moves: `C(r+d,d)`. Through a given point,
multiply the two halves. If a diagonal move is allowed, split into cases by how
many diagonals are used and add.

0-dominated / ballot: A never behind, m votes for A and n for B, is
`C(m+n,n) − C(m+n,n−1)` — the subtracted term counts bad paths by reflection.
Exam I Q13: A wins 5–3, so `C(8,3) − C(8,2) = 56 − 28 = 28`, probability
`28/56 = 1/2`.

**Exam I answers** (menu `5 → EXAM I ANSWERS`, all verified):

| Q | | Q | |
|---|---|---|---|
| 1 | 243, 80, 90 | 9 | C(8,3)·2⁵ = 1792 |
| 3(C) | C(n+1,3) | 10 | C(6,3) = 20 |
| 4 | 9·9·9 = 729 | 11 | 26⁵−25⁵ = 2,115,751 |
| 5 | 3 + 90 = 93 | 12 | 36, 15, 15, 66, 36 |
| 6(B) | 48,384 | 13 | 28, probability ½ |
| 6(C) | 2ⁿ, 0, 2^(n−1) | 7 | 720/7776 = 5/54 |
| 8 | 13·4·12·6 = 3744 | | |

## Off-scope material (kept, marked `*`)

Reachable from menu item 7, all clearly flagged:

- **Standard Problems #1–#7** — the Section A and B ones, formulas only; the
  fuller treatment is under Exam I Review on menu 5
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
