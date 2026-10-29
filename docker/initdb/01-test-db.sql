-- Separate database so the test-suite (which wipes and re-migrates) never touches dev data.
CREATE DATABASE wallet_test OWNER wallet;
