# Isolation lab (Milestone 2, Step 1)

Open **two `psql` sessions** side by side (A and B) against the compose database:

```bash
docker compose exec db psql -U wallet -d wallet     # run once in each terminal
```

> The commands below are exact. The "Expected" blocks describe what PostgreSQL 16 does; **paste your own terminal output into the "Your output" blocks** when you run them, that is the deliverable the brief asks for.

## Setup (either session)

```sql
INSERT INTO users(name,email,phone) VALUES ('Lab One','lab1@example.com','lab-1'),('Lab Two','lab2@example.com','lab-2');
INSERT INTO accounts(user_id,kind,label,balance)
  SELECT id,'USER_WALLET','main',1000 FROM users WHERE email IN ('lab1@example.com','lab2@example.com') RETURNING id;
-- note the two ids as W1 and W2 and substitute them below
```

## Case 1: READ COMMITTED (the default)

```sql
-- A
BEGIN;                                              -- READ COMMITTED by default
SELECT balance FROM accounts WHERE id = W1;         -- 1000
-- B
UPDATE accounts SET balance = 900 WHERE id = W1;    -- autocommit: committed immediately
-- A
SELECT balance FROM accounts WHERE id = W1;         -- same transaction, second read
COMMIT;
```

**Output:**
```
 balance
---------
     900
(1 row)
```

## Case 2: REPEATABLE READ

```sql
UPDATE accounts SET balance = 1000 WHERE id = W1;   -- reset
-- A
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM accounts WHERE id = W1;         -- 1000  (snapshot taken here)
-- B
UPDATE accounts SET balance = 900 WHERE id = W1;
-- A
SELECT balance FROM accounts WHERE id = W1;         -- ?
UPDATE accounts SET balance = balance - 50 WHERE id = W1;   -- ?
ROLLBACK;
```

**Your output:**
```
wallet=*# SELECT balance FROM accounts WHERE id = 6;
 balance
---------
    1000
(1 row)

wallet=*# UPDATE accounts SET balance = balance - 50 WHERE id = 6;
ERROR:  could not serialize access due to concurrent update
```

## Case 3: SERIALIZABLE (write skew)

Rule we want to keep: *W1 + W2 must stay ≥ 1000*. Both balances are currently 1000.

```sql
UPDATE accounts SET balance = 1000 WHERE id IN (W1, W2);   -- reset
-- A
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT sum(balance) FROM accounts WHERE id IN (W1, W2);    -- 2000: "plenty, I can withdraw 1000"
-- B
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT sum(balance) FROM accounts WHERE id IN (W1, W2);    -- 2000
-- A
UPDATE accounts SET balance = balance - 1000 WHERE id = W1;
COMMIT;                                                    -- succeeds
-- B
UPDATE accounts SET balance = balance - 1000 WHERE id = W2;
COMMIT;                                                    -- ?
```

**Your output:**
```
wallet=*# UPDATE accounts SET balance = balance - 1000 WHERE id = 7;
COMMIT;
ERROR:  could not serialize access due to read/write dependencies among transactions
DETAIL:  Reason code: Canceled on identification as a pivot, during write.
HINT:  The transaction might succeed if retried.
ROLLBACK
```

## Case 4: REPEATABLE READ with a join

```sql
UPDATE accounts SET balance = 1000 WHERE id = W1;   -- reset
-- A
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT a.balance, count(t.id) AS transfers
FROM accounts a LEFT JOIN transfers t ON t.from_account_id = a.id
WHERE a.id = W1 GROUP BY a.balance;                 -- 1000 | 0
-- B
UPDATE accounts SET balance = 900 WHERE id = W1;
INSERT INTO transfers(type,status,amount,from_account_id,to_account_id)
  VALUES ('PEER','COMPLETED',100,W1,W2);
-- A
SELECT a.balance, count(t.id) AS transfers
FROM accounts a LEFT JOIN transfers t ON t.from_account_id = a.id
WHERE a.id = W1 GROUP BY a.balance;                 -- ?
ROLLBACK;
```

**Your output:**
```
wallet=*# SELECT a.balance, count(t.id) AS transfers
FROM accounts a LEFT JOIN transfers t ON t.from_account_id = a.id
WHERE a.id = 6 GROUP BY a.balance;
 balance | transfers
---------+-----------
    1000 |         0
(1 row)
```

## Cleanup
```sql
DELETE FROM transfers WHERE from_account_id IN (W1,W2) OR to_account_id IN (W1,W2);
DELETE FROM accounts WHERE id IN (W1,W2);
DELETE FROM users WHERE email IN ('lab1@example.com','lab2@example.com');
```
