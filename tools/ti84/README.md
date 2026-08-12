# MAT 3470 — TI-84 combinatorics reference

A TI-BASIC program (`MAT3470`) that pages through the formulas and "when do I
use this" cues for MAT 3470 Exam II, plus a calculator section that actually
computes the awkward ones (onto functions, derangements, multinomials).

Built from the August 5, 2024 Exam II and its key, so the notation matches the
course — `T(n,k)`, `(n choose r)_R`, and the `S₀ − S₁ + S₂ − …` inclusion/
exclusion setup.

> **Before you use this:** the exam paper states "NO NOTES, BOOKS, CELL PHONE
> NOR INTERNET IS ALLOWED. CALCULATORS ARE ALLOWED." A formula reference on a
> permitted calculator is the usual way around a no-notes rule. Check with your
> instructor whether calculator programs are allowed before a graded sitting.
> Nothing here is disguised or hidden from a memory check.

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
 1 BALLS+BOXES     the full n-balls-into-k-boxes taxonomy (6 pages)
 2 T-TRIANGLE      onto functions: definition, recurrence, rows 1-6, closed form
 3 INCL-EXCL       S_k notation, derangements, no-two-adjacent (7 pages)
 4 MULTINOMIAL     trinomial theorem, the plug-in tricks, coefficient extraction
 5 GF+RECURRENCE   generating functions / characteristic-equation method
 6 CALCULATE       computes things
 7 QUIT

CALCULATE
 1 C AND P           nCr and nPr together
 2 C WITH REPEATS    (n choose r)_R = C(n+r-1, r)
 3 T(N,K) ONTO       onto functions, via the alternating sum
 4 MULTINOMIAL       enter the part sizes, get M!/(m₁!m₂!…)
 5 DERANGEMENT       D(n), via the same inclusion/exclusion the key uses
 6 BALLS+BOXES       asks distinct/identical and empty/no-empty, then answers
 7 MAIN MENU
```

## The cheat sheet itself

### The balls-and-boxes table

`n` balls into `k` boxes. Everything on this exam reduces to picking a cell.

| | empty boxes allowed | no box empty |
|---|---|---|
| **distinct balls, distinct boxes** | `kⁿ` | `T(n,k)` |
| **identical balls, distinct boxes** | `C(n+k−1, n)` = `(k choose n)_R` | `C(n−1, k−1)` |

Fixed box sizes n₁…n_k with distinct balls: `n!/(n₁!·n₂!·…·n_k!)` — the
multinomial coefficient.

Worked from the exam: 6 distinct balls into 5 distinct boxes, none empty, is
`T(6,5) = 1800`. Six *identical* balls into 5 distinct boxes is
`(5 choose 6)_R = C(10,6) = 210`.

### T-triangle (onto functions)

`T(n,k)` = number of onto functions from an n-set to a k-set = number of ways to
put n distinct balls into k distinct boxes with no box empty.

```
T(n,k) = k · [ T(n−1,k) + T(n−1,k−1) ]      T(n,1) = 1,  T(n,n) = n!

row 1   1
row 2   1    2
row 3   1    6    6
row 4   1   14   36    24
row 5   1   30  150   240   120
row 6   1   62  540  1560  1800   720
```

Closed form: `T(n,k) = Σᵢ (−1)ⁱ C(k,i) (k−i)ⁿ`, i = 0…k. (Also `T(n,k) = k!·S(n,k)`
with Stirling numbers, if the textbook uses those.)

### Inclusion / exclusion

Name the *bad* events A₁…A_n. Then `S₀` = total, `S₁` = Σ|Aᵢ|, `S₂` = Σ|Aᵢ∩Aⱼ|,
and generally `S_k` = the sum over all k-way overlaps.

```
none of them happen:   |A₁′ ∩ … ∩ A_n′| = S₀ − S₁ + S₂ − S₃ + …
at least one happens:  S₁ − S₂ + S₃ − …  =  S₀ − (none)
```

Three standard dressings of it on this exam:

- **Divisibility.** 1…210 divisible by 2, 3, 5 or 7: S₁ = 105+70+42+30 = 247,
  S₂ = 101, S₃ = 17, S₄ = 1, so none = 210−247+101−17+1 = 48, and the answer is
  210 − 48 = **162**.
- **Derangements.** Aᵢ = "item i is in its right place".
  `D(n) = n! − C(n,1)(n−1)! + C(n,2)(n−2)! − …`; D₅ = 120−120+60−20+5−1 = **44**.
  Values: 0, 1, 2, 9, 44, 265, 1854. Shortcut `D(n) = round(n!/e)`.
- **No two adjacent equal**, string `11223344`. Aᵢ = "the two i's are glued".
  S₀ = 8!/2⁴ = 2520; gluing j pairs gives `(8−j)!/2^(4−j)` each, so
  S₁ = 4·7!/2³ = 2520, S₂ = 6·6!/2² = 1080, S₃ = 4·5!/2 = 240, S₄ = 4! = 24 →
  2520−2520+1080−240+24 = **864**.
- **No empty box with two colors** (5 red + 4 blue into 3 boxes): Aᵢ = "box i
  empty", S₀ = (3 choose 5)_R·(3 choose 4)_R = C(7,5)·C(6,4) = 315, |Aᵢ| = 30,
  |Aᵢ∩Aⱼ| = 1, triple = 0 → 315 − 90 + 3 − 0 = **228**.

### Multinomial / trinomial

```
(m; m₁,m₂,…,m_k) = m! / (m₁!·m₂!·…·m_k!),   m₁+…+m_k = m

(a+b+c)^m = Σ (m; m₁,m₂,m₃) a^m₁ b^m₂ c^m₃    over all mᵢ ≥ 0 with Σmᵢ = m
```

The exam's three plug-in tricks:

- Set `a = b = c = 1` → the sum of all trinomial coefficients is `3^m`.
- If `a + b + c = 1` (e.g. ½ + ⅙ + ⅓) → the weighted sum is `1^m = 1`.
- Restrict to every `mᵢ ≥ 1` → the sum is `T(m,k)`. For m = 6, k = 3: **540**.

Number of terms in `(a+b+c)^m` is `C(m+2, 2)`.

Coefficient extraction: in `(x + 2y + 3z)^10`, the `x²y³z⁵` coefficient is
`(10; 2,3,5)·2³·3⁵ = 2520 · 8 · 243 = 4,898,880`.

> The scanned key reads `489880` here, which is one digit short — 2520·1944 is
> 4,898,880. Worth confirming with your instructor, but the arithmetic is
> unambiguous.

### Generating functions

One factor per item type; the exponent is how many of that item you use;
multiply out and read the coefficient of `x^target`.

```
any amount    1 + x + x² + …        = 1/(1−x)
at most 3     1 + x + x² + x³
at least 1    x + x² + x³ + …
```

Exam problem: 3 quarters, 6 dimes, 10 nickels making 100¢ →

```
(1 + x²⁵ + x⁵⁰ + x⁷⁵)(1 + x¹⁰ + … + x⁶⁰)(1 + x⁵ + … + x⁵⁰)
```

coefficient of `x¹⁰⁰` = **15**.

### Linear recurrences

For `x_{n+2} = a·x_{n+1} + b·x_n`:

1. Characteristic equation `r² = a·r + b`, i.e. `r² − a·r − b = 0`.
2. Solve for roots r₁, r₂ (the course calls them eigenvalues).
3. `x_n = A·r₁ⁿ + B·r₂ⁿ`. If r₁ = r₂ = r, use `x_n = (A + B·n)·rⁿ`.
4. Plug in the two known terms, solve for A and B.

Exam problem: `x_{n+2} = x_{n+1} + 2x_n`, x₁ = 1, x₂ = 3 → `r² − r − 2 = 0` →
r = 2, −1 → `x_n = ⅔·2ⁿ + ⅓·(−1)ⁿ`, so `x₃₀ = (2³¹ + 1)/3 = 715,827,883`.

## Limits worth knowing

- `!` overflows past 69! on a TI-84, so the multinomial calculator caps out at
  a total of 69. `nCr` is computed without full factorials and goes much higher.
- `nCr` with r > n returns 0 rather than erroring.
- The T(n,k) and derangement routines use the alternating sums above, so they
  match the key's method exactly rather than a lookup table.
