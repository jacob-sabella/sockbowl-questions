-- Atomic daily-quota check-and-increment (plan m4-limits section 2.3).
-- Shared verbatim by sockbowl-game and sockbowl-questions.
--
-- KEYS[1]  counter key   usage:{id}:{metric}:d:{yyyyMMdd} (or usage:global:...)
-- KEYS[2]  override hash quota:override:{sub}, or '' for none
-- ARGV[1]  default limit (-1 = unlimited)
-- ARGV[2]  counter TTL in seconds, set when the counter is created
-- ARGV[3]  amount to add
-- ARGV[4]  metric name (field of the override hash)
--
-- Returns {allowed (1/0), used, effectiveLimit}. On rejection the increment
-- is undone, so `used` is the unchanged count.
local limit = tonumber(ARGV[1])
if KEYS[2] ~= '' then
  local override = redis.call('HGET', KEYS[2], ARGV[4])
  if override then
    limit = tonumber(override)
  end
end
local amount = tonumber(ARGV[3])
local used = redis.call('INCRBY', KEYS[1], amount)
if used == amount then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
end
if limit >= 0 and used > limit then
  used = redis.call('DECRBY', KEYS[1], amount)
  return {0, used, limit}
end
return {1, used, limit}
