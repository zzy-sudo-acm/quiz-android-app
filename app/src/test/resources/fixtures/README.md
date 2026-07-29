# DOCX regression fixtures

These files are synthetic and safe to redistribute. They contain no user documents, private data,
or copyrighted question bank.

- `standard-regression.docx`: real OOXML ZIP with Word automatic numbering, single/multiple/true-false
  questions, explanations, knowledge, two stem images, two option images, and one malformed question.
  Expected deterministic result: 3 accepted candidates and 1 visible rejection.
- `smart-regression.docx`: multiple questions in one paragraph, inline options, a table question,
  headings between questions, a final answer summary, and an oversized paragraph.
