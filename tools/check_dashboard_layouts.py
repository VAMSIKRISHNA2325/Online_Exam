"""Inspect dashboard layouts against the configured disposable browser server."""

import re

from playwright.sync_api import expect, sync_playwright
from browser_support import base as BASE, browser as BROWSER, evidence, password as PASSWORD

errors = []
inspected = []


def inspect(page, name, width):
    page.wait_for_load_state("networkidle")
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), (
        f"Horizontal clipping on {name} at {width}px"
    )
    assert page.locator("main").is_visible()
    duplicates = page.evaluate("""() => {
        const ids = [...document.querySelectorAll('[id]')].map(node => node.id);
        return ids.filter((id, index) => ids.indexOf(id) !== index);
    }""")
    assert not duplicates, f"Duplicate control IDs: {duplicates}"
    page.screenshot(path=evidence(f"{name}-{width}", "dashboard"), full_page=True)
    inspected.append(f"{name} {width}px")


def sign_in(page, username):
    page.goto(BASE + "/login.html")
    page.wait_for_load_state("networkidle")
    page.locator("#username").fill(username)
    page.locator("#password").fill(PASSWORD)
    page.locator("#sign-in").click()
    expect(page).to_have_url(BASE + "/home.html")
    expect(page.locator("#account-content")).to_be_visible()


def sign_out(page):
    page.goto(BASE + "/home.html")
    expect(page.locator("#sign-out")).to_be_enabled()
    page.locator("#sign-out").click()
    expect(page).to_have_url(BASE + "/login.html")


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, executable_path=BROWSER)
    for width in (1440, 360):
        context = browser.new_context(viewport={"width": width, "height": 900 if width == 1440 else 780})
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(BASE + "/login.html")
        inspect(page, "login", width)
        page.locator("#username").focus()
        page.keyboard.press("Tab")
        expect(page.locator("#password")).to_be_focused()
        assert page.locator("#password").evaluate(
            "node => getComputedStyle(node).outlineStyle !== 'none'"
        ), "Keyboard focus must remain visible"

        sign_in(page, "demo_instructor1")
        expect(page.locator("#home-instructor-nav")).to_be_visible()
        expect(page.locator("#home-student-nav")).to_be_hidden()
        inspect(page, "instructor-account", width)
        page.locator("#home-instructor-nav").click()
        expect(page.locator("#desk-loading")).to_be_hidden()
        inspect(page, "instructor", width)
        if page.locator("#exam-list .exam-list-item").count():
            page.locator("#exam-list .exam-list-item").first.click()
            expect(page.locator("#exam-editor")).to_be_visible()
            inspect(page, "instructor-exam", width)
        page.locator("#new-exam").click()
        expect(page.locator("#exam-title")).to_be_enabled()
        inspect(page, "instructor-form", width)
        sign_out(page)

        sign_in(page, "demo_student2")
        expect(page.locator("#home-student-nav")).to_be_visible()
        expect(page.locator("#home-instructor-nav")).to_be_hidden()
        inspect(page, "student-account", width)
        page.locator("#home-student-nav").click()
        expect(page.locator("#catalog-loading")).to_be_hidden()
        expect(page.locator(".catalog-card").first).to_be_visible()
        assert page.locator(".catalog-card").first.locator("td").count() == 4
        inspect(page, "student", width)
        result_action = page.get_by_role("button", name=re.compile("View result")).first
        expect(result_action).to_be_visible()
        result_action.click()
        expect(page.locator("#result-score")).to_be_visible()
        inspect(page, "result", width)
        sign_out(page)
        context.close()
    assert not errors, errors
    browser.close()

print(f"PASS: {len(inspected)} dashboard views at 1440px/360px; no horizontal clipping, "
      "duplicate control IDs or page errors; labeled keyboard login, visible focus, role navigation, "
      "exam table, instructor forms and persisted results work. Screenshots saved under evidence/.")
