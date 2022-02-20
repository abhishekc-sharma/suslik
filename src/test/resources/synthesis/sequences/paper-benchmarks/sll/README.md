# Paper-Benchmarks/SLL
Port of singly-linked list tests from
`src/test/resources/synthesis/paper-benchmarks/sll` to use sequences instead

## Works
| Name         | Any Notes                                              |
|--------------|--------------------------------------------------------|
|ssl-singleton |                                                        |
|ssl-dupleton  | I don't think this test works with normal linked lists! Glad it works here.|
|ssl-free      |                                                        |
|ssl-copy      |                                                        |

## Doesn't work
| Name         | Reason                                      |
|--------------|---------------------------------------------|
| ssl-init     | Haven't defined `<=` (subset) for sequences (OpSubinterval) |