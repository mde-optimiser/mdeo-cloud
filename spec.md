# MDEOptimiser: Atomic Consistency Preserving Search Operators (aCPSOs)

MDEOptimiser now supports the generation of atomic consistency preserving search operators (aCPSOs). aCPSOs are mutation operators that ensure multiplicity constraint satisfaction after their application.

A description of the currently supported search operators is given in the two sections below. The first section shows node manipulation patterns supported and the second includes edge manipulation cases supported.

The operators below assume the multiplicity pattern between two nodes as shown in the original figure: `![Multiplicity Patterns](/images/mutation_generation/multiplicity-patterns.png)`

## Node Manipulation

A list of the supported node manipulation multiplicities is included below. The first section details the node creation operators. The second section details the node deletion operators.

### Create Node Operators

**Conditions where k >= 0, l > k, l < *:**
* **When n=0:** c A
* **When n=1 and m > n:** Create A add n B (f#l A) | Create A lb r single B
* **When n > 1 and m > n:** Create A add n B | Create A lb r single B | Create A lb r many B
* **When n = m:** Create A add n B (f#l A)

**Conditions where k >= 0, l = *:**
* **When n=1 and m > n:** Create A add n B
*(Note: No operators specified for n=0, n>1 and m>n, or n=m under this condition)*

**Conditions where k = l:**
* **When n=1 and m > n:** Create A lb r single B
* **When n > 1 and m > n:** Create A add n B | Create A lb r single B | Create A lb r many B
* **When n = m:** N/A
*(Note: No operator specified for n=0 under this condition)*

---

### Delete Node Operators

**Conditions where k = 0:**
* **When m > n and m < *:** Delete A
*(Note: No operators specified for m=* or n=m under this condition)*

**Conditions where k > 0, l > k:**
* **When m > n and m < *:** Delete A (require each B still has #k A)
*(Note: No operators specified for m=* or n=m under this condition)*

**Conditions where k=l=1:**
* **When m > n and m < *:** Delete A r lb sg B (f#m A)
* **When m = *:** Delete A r lb sg B
* **When n = m:** N/A

**Conditions where k=l > 1:**
* **When m > n and m < *:** Delete A r lb sg B (f#m A) | d A r lb mm B (f#m A)
* **When m = *:** Delete A r lb sg B | d A r lb mm B
* **When n = m:** N/A

---

## Edge Manipulation

The list of supported multiplicities for generating edge manipulation search operators is included below. The first section contains the edge creation supported multiplicities and the generated operators. The second section contains the operators generated for edge removal.

### Edge Creation Operators

**Conditions where l < *:**
* **When m < *:** Add edge NAC A B
* **When m = *:** Add edge NAC B
* **When n = m:** Swap edge

**Conditions where l = *:**
* **When m < *:** Add edge NAC A
* **When m = *:** Add edge
* **When n = m:** Swap edge

**Conditions where k = l:**
* **When m < *:** Change edge (P/N A)
* **When m = *:** Change edge  (P/N A)
* **When n = m:** Swap edge

---

### Edge Removal Operators

**Conditions where k = 0:**
* **When n = 0:** Remove edge
* **When n > 0:** Remove edge PAC A
* **When n = m:** Swap Edge

**Conditions where k > 0:**
* **When n = 0:** Remove edge PAC B
* **When n > 0:** Remove edge PAC AB
* **When n = m:** Swap Edge

**Conditions where k = l:**
* **When n = 0:** Change edge (P/N A)
* **When n > 0:** Change edge (P/N A)
* **When n = m:** Swap Edge