#!/usr/bin/env python3
"""Regenerate prompts.json.

The corpus is generated rather than hand-typed so that the long-context prompt is
deterministic: it is assembled from a fixed passage by a fixed rule, so anyone can rebuild
byte-identical fixtures and get the same checksums. Editing prompts.json by hand defeats that
— change this script and re-run it instead.

    python3 benchmark/fixtures/generate_prompts.py

Token counts in the output are approximations for sizing only, from a crude chars/4 rule. The
harness never uses them: results carry whatever the engine itself counted.
"""

from __future__ import annotations

import hashlib
import json
import pathlib

CORPUS_VERSION = "1"

# One self-contained passage with no proper nouns a model might have memorised, repeated to
# reach a target length. Deliberately dull: the point is a fixed number of input tokens, not
# an interesting completion.
FILLER_PARAGRAPH = (
    "Section {n}. The maintenance log for pumping station {n} records the following. "
    "The intake valve was inspected on the first day of the cycle and found to be within "
    "tolerance. Flow through the primary channel averaged {flow} litres per minute over the "
    "period, which is {delta} litres above the seasonal baseline. The secondary channel was "
    "closed for cleaning on the third day and returned to service on the fourth. Two filter "
    "cartridges were replaced. The operator noted a faint vibration in the housing at high "
    "flow rates and scheduled a follow-up inspection. No alarms were raised during the cycle "
    "and no unplanned downtime was recorded. "
)


def filler(sections: int) -> str:
    return "".join(
        FILLER_PARAGRAPH.format(n=i, flow=400 + i * 7, delta=i * 3)
        for i in range(1, sections + 1)
    )


SUMMARY_SOURCE = filler(3)

# Half a section, for the ~100-token case: a whole one overshoots.
SHORT_NOTE = (
    "Section 1. The intake valve was inspected on the first day of the cycle and found to be "
    "within tolerance. The secondary channel was closed for cleaning on the third day and "
    "returned to service on the fourth. Two filter cartridges were replaced. "
)

PROMPTS = [
    {
        "id": "short_generation_v1",
        "category": "short_generation",
        "text": (
            "Write a short paragraph explaining what a tide is and why it happens. "
            "Keep it under one hundred words."
        ),
        "notes": "Targets roughly 100 generated tokens with maxNewTokens=128.",
    },
    {
        "id": "long_generation_v1",
        "category": "long_generation",
        "text": (
            "Write a detailed explanation of how a bicycle derailleur shifts a chain between "
            "sprockets. Cover the cable tension, the parallelogram mechanism, the jockey "
            "wheels, and what happens when the system is out of adjustment. Use complete "
            "sentences and do not use bullet points."
        ),
        "notes": "Targets roughly 500 generated tokens with maxNewTokens=512.",
    },
    {
        "id": "short_context_v1",
        "category": "short_context",
        "text": (
            "Read the following note and answer the question after it.\n\n"
            + SHORT_NOTE
            + "\nQuestion: how many filter cartridges were replaced, and on which day did the "
            "secondary channel return to service?"
        ),
        "notes": "Around 100 input tokens.",
    },
    {
        "id": "long_context_v1",
        "category": "long_context",
        "text": (
            "Read the following maintenance log and answer the question after it.\n\n"
            + filler(11)
            + "\nQuestion: which section reports the highest average flow, and what was the "
            "value?"
        ),
        "notes": "Around 2000 input tokens. Sections are numbered so the answer is checkable.",
    },
    {
        "id": "reasoning_v1",
        "category": "reasoning",
        "text": (
            "A train leaves a station at 09:00 travelling at 60 kilometres per hour. A second "
            "train leaves the same station at 09:45 travelling at 90 kilometres per hour along "
            "the same track. Work through the problem step by step: state what each train has "
            "travelled at 09:45, set up the equation for when the second train catches the "
            "first, solve it, and state the catch-up time and the distance from the station. "
            "Show every step of the arithmetic."
        ),
        "notes": (
            "Deterministic answer (11:15, 135 km) so a degenerate run is recognisable, and "
            "the step-by-step instruction pushes output into the several-hundred-token range."
        ),
    },
    {
        "id": "summarization_v1",
        "category": "summarization",
        "text": (
            "Summarise the maintenance log below in exactly three sentences. The first "
            "sentence must state the overall status, the second must list every part that was "
            "replaced, and the third must state any follow-up work that was scheduled.\n\n"
            + SUMMARY_SOURCE
        ),
        "notes": "Fixed source text and a fixed output format, so summaries are comparable.",
    },
]


def main() -> None:
    prompts = []
    for prompt in PROMPTS:
        text = prompt["text"]
        prompts.append(
            {
                "id": prompt["id"],
                "category": prompt["category"],
                "text": text,
                "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                "approxInputTokens": max(1, len(text) // 4),
                "notes": prompt["notes"],
            }
        )

    out = pathlib.Path(__file__).with_name("prompts.json")
    out.write_text(
        json.dumps({"corpusVersion": CORPUS_VERSION, "prompts": prompts}, indent=2) + "\n",
        encoding="utf-8",
    )
    for prompt in prompts:
        print(f"{prompt['id']:24} ~{prompt['approxInputTokens']:5} tokens  {prompt['sha256'][:12]}")
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
