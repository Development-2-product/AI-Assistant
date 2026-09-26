from app.services import safety


def test_detects_new_amount():
    assert safety.violates_money_rule("Sure, ₹60,000 works", "Can you pay ₹48,000?")


def test_allows_repeating_existing_amount_without_commitment():
    assert not safety.violates_money_rule("I'll check the ₹48,000 invoice and get back to you", "Pay ₹48,000?")


def test_commit_words():
    assert safety.violates_money_rule("Deal, payment done", "")


def test_clean_reply_strips_quotes_and_label():
    assert safety.clean_reply('Reply: "Hi there"', "whatsapp") == "Hi there"


def test_crisis_detection():
    assert safety.detect_crisis("i don't want to live anymore")
    assert not safety.detect_crisis("I'm tired today")
