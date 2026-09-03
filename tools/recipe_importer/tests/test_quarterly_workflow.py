from pathlib import Path


WORKFLOW = (
    Path(__file__).resolve().parents[3]
    / ".github"
    / "workflows"
    / "quarterly-catalog-refresh.yml"
)


def _workflow_sections() -> tuple[str, str]:
    text = WORKFLOW.read_text(encoding="utf-8")
    manual, scheduled = text.split("\n  scheduled_refresh:", maxsplit=1)
    return manual, scheduled


def test_manual_refresh_runs_each_provider_in_its_own_matrix_job() -> None:
    manual, _ = _workflow_sections()

    assert "fail-fast: false" in manual
    for source in ("akis", "argiro", "gastronomos"):
        assert f"- source: {source}" in manual
        assert manual.count(f"if: matrix.source == '{source}'") == 2
    assert "name: manual-catalog-audit-${{ matrix.source }}-${{ github.run_id }}" in manual
    assert "tools/recipe_importer/output/${{ matrix.source }}-greek-full.manifest.json" in manual
    assert "tools/recipe_importer/output/${{ matrix.source }}-greek-full.failures.json" in manual
    assert "id-token: write" not in manual
    assert "environment: recipe-catalog-production" not in manual
    assert "GCP_WORKLOAD_IDENTITY_PROVIDER" not in manual
    assert "--commit" not in manual


def test_quarterly_jobs_respect_hosted_runner_limit_and_exact_commands() -> None:
    manual, scheduled = _workflow_sections()
    text = WORKFLOW.read_text(encoding="utf-8")

    assert "timeout-minutes: 600" not in text
    assert text.count("timeout-minutes: 360") == 2
    commands = (
        "python tools/recipe_importer/crawl_catalog.py --i-have-permission",
        "python tools/recipe_importer/crawl_argiro.py --i-have-argiro-permission",
        "python tools/recipe_importer/crawl_gastronomos.py --i-have-gastronomos-permission",
    )
    for command in commands:
        assert manual.count(command) == 1
        assert scheduled.count(command) == 1
    assert scheduled.count("id-token: write") == 1
    assert scheduled.count("environment: recipe-catalog-production") == 1
    for day in (1, 2, 3):
        schedule = f"17 3 {day} 1,4,7,10 *"
        assert text.count(f'- cron: "{schedule}"') == 1
        assert scheduled.count(f"github.event.schedule == '{schedule}'") == 3
    assert 'cron: "0 3 ' not in text
    assert "github.event.schedule == '0 3 " not in scheduled
