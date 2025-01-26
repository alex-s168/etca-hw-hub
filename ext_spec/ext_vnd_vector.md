# Vendor Vector Extension

**EXTREMLY WORK IN PROGRESS**

simple and usable variable-length vector extension, with only integer data. (float data is an extension)

## Definitions
### Scalar
A scalar is a element in an vector and a value that can be stored in a register in the base architecture.

### Vector
Can be compared to an array of a specific type.
Most of the time: A sub-view of an in-memory array.

### Vector Register
A register that is at least 64 bits wide and stores exactly one vector.

Data in a vector register is contigious.

When re-configuring or using a vector register to a different element type or vector length than the currently stored vecotr,
the data is bit-casted.

### Vector Mask
A vector mask can be compared to a boolean array with the same length of a different array, used for selecting specific elements.

A vector mask is just a different interpretation of a vector of `i1` (1-bit integer) elements.

They are stored in vector registers and can be used as vector of `i1`.

### Special Vector Masks
There are some special vector mask registers:
- `cvm` - vector mask of the carry flags of each element
- `zvm` - vector mask of the zero flags of each element
- `vvm` - vector mask of the overflow flags of each element

These registers can be read from and written to.

Operations like `vadd` store the carry flag of each element in the `cvm`.
Operations like `vadc` load the carry flag of each element from the `cvm`.

## Vector element types
- `i1`  - 1  bit integer
- `i8`  - 8  bit integer (->1)
- `i16` - 16 bit integer
- `i32` - 32 bit integer (->1)
- `i64` - 64 bit integer (->1)

1) these types are valid vector types,
even if the corresponding scalar extension is not implemented.
The main reason for doing that is to make code more portable.

a future floating point extensions might add:
- `f16` - 16 bit IEEE 754 floating point number
- `f32` - 32 bit IEEE 754 floating point number
- `f64` - 64 bit IEEE 754 floating point number

TODO: non-standard floats

## Vector Operation Types
All vector operations take in any amount of vectors and scalars and output one scalar or vector.

Many vector operations can be masked using a vector mask.

### Element-wise Operations
Apply a scalar operation on each element in a vector

If this operation is masked, only the elements in the destination vector register are overwritten where the mask is true.

Example:
```
+--+--+--+--+
| 1| 2| 3| 4|
+--+--+--+--+
   negate
     \/
+--+--+--+--+
|-1|-2|-3|-4|
+--+--+--+--+
```

### Reduction Operations
Combine all vector elements in a specific way to generate a scalar.

If this operation is maked, only the elements where the mask is true are combined.

Example:
```
+--+--+--+--+
| 1| 2| 3| 4|
+--+--+--+--+
  \  |  |  |
   [+]  |  |
     \  |  |
      [+]  |
        \  |
	 [+]
	  |
        +--+
        |10|
        +--+
```

### Move Operations
Copy all elements from a vector into a vector register.

If this operation is masked, only the elements in the destination vector register are overwritten where the mask is true.

Example:
```
+--+--+--+--+
| 1| 2| 3| 4|
+--+--+--+--+
    move
+--+--+--+--+
| 1| 2| 3| 4|
+--+--+--+--+
```

## `vcfg` behaviour
`vcfg` configures vector length of a range of vector registers.
It always bitcasts the existing data in the affected registers into the new registers.
Data outside of the vector length will never be affected by operations.
Reducing the vector length, performing read-only operations and extending the vector length has to result in the vector register to contain the same data as in the beginning.

## Instructions
| format | description |
| - | - |
| `vbrdcst {mask?} [destination vector register], [source scalar register or immediate]` | copy the scalar into each element of the destination vector register |
| `vOP {mask?} [destination and first source vector register], [second source vector register]` | elementwise perform operation `OP` |
| `vcfg [dst scalar register], [src scalar register], [vector element type], [vector register range]` | configure the vector registers in the range with the type and the maximum element count in the source scalar register, and stores the actual element count used into the destination scalar register |
| `vbmov {mask?} [destination vector register], [source vector or scalar register]` | bitcast the source data into the destination vector register with a optional mask for elements in the destination register |
| `vsxmov {mask?} [destination vector register], [source vector register]` | move each element into the corresponding spot in the destination register (if the mask is true for that element), sign extending / truncating each element to the destination size |
| `vzxmov {mask?} [destination vector register], [source vector register]` | move each element into the corresponding spot in the destination register (if the mask is true for that element), zero extending / truncating each element to the destination size |
| `vld {mask?} [destination vector register], [source scalar register]` | load all data from memory at the address specified by the source scalar register (only where the mask is true) into the destination vector register. (1) (2) (3) (4) (5) |
| `vst {mask?} [destination scalar register], [source vector register]` | store all the data of a vector register into the memory at the address specified by the source scalar register, overwriting only the data in the memory where the mask is true. (1) (2) (3) (4) (5) |
| `vdil [destination vector register], [source vector register], [begin offset immediate], [stride immediate]` | move every [stride] element of the source vector register starting at element [begin] into the destination vector register, zero extending / truncating. |
| `vill [destination vector register], [source vector register], [begin offset immediate], [stride immediate]` | move every element of the source vector register into the destination vector register at [stride] times index + [begin]. |

TODO: alternative to vill/vdil:
- remove offset and stride from vill/vdil
- vspace and vunspace [1|8|16|32|64] that creates a [arg] -bits wide gap between element in the vec
- note that vill/vdil can be implemented with vspace+vor/vunspace

TODO: SCLAR shift left with extending LSB

1) these operations can not be used with MO1 and MO2
2) these operations do not cause a access fault for memory locations that are not actually loaded
3) these operations might be slow if a mask is specified
4) the memory address is allowed to be unaligned.
5) these operations zero-extend `i1` vectors to the next byte

## Examples

### Saturating Add
```asm
; a0 = arr len
; a1 = arr ptr
; a2 = what to add
sat_add:
	vcfg	t0, a0, i8, v0->v1

.loop:
	cmp	a0, 0
	je	.end
	sub	a0, t0

	vld	v0, [a1]
	vbrdcst	v1, a2
	vadd	v0, v1
	vbrdcst	{cvm} v0, 255
	vst	[a1], v0

	add	a1, t0
	jmp	.loop

.end:
	ret
```

### AOS to SAO
ArrayOfStructs to StructOfArrays
```
; a0 = arr len
; a1 = arr ptr 
; a2 = element 0 dest ptr
; a3 = element 1 dest ptr
aos_to_sao:
	mov	t0, a0
	add	t0, a0
	vcfg	t0, t0, i8, v0		; all data
	vcfg	t0, a0, i8, v1->v2	; elem 0 and 1

.loop:
	cmp a0, 0
	je .end
	sub a0, t0

	vld v0, [a1]
	vdil v1, v0, 2, 0
	vdil v2, v0, 2, 1
	vst [a2], v1
	vst [a3], v2

	add a1, t0
	add a2, t0
	add a3, t0

	jmp .loop

.end:
	ret
```
