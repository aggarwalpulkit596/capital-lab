const { test, expect } = require('@playwright/test');

test('developer can review fee, request once, and inspect the same funding in finance', async ({
  page,
}) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/');
  await expect(page.getByTestId('advance-principal')).toHaveText('$200.00');
  await expect(page.getByTestId('advance-fee')).toHaveText('−$5.00');
  await expect(page.getByTestId('advance-net')).toHaveText('$195.00');
  await page.getByRole('button', { name: 'Request $195.00 payout' }).click();
  await expect(page.getByRole('button', { name: '✓ Payout sent' })).toBeDisabled();
  await page.reload();
  await expect(page.getByRole('button', { name: '✓ Payout sent' })).toBeDisabled();
  await page.getByRole('button', { name: 'View in finance' }).click();
  const operations = page.locator('#operations');
  await expect(operations).toContainText('Fees withheld');
  await expect(operations.locator('.metric').filter({ hasText: 'Fees withheld' })).toContainText(
    '$5.00',
  );
  await expect(operations.locator('tbody tr')).toHaveCount(1);
  await operations.getByRole('tab', { name: 'Ledger', exact: true }).click();
  await expect(operations).toContainText('BALANCED');
  await expect(operations).toContainText('deferred fee');
  await operations.getByRole('tab', { name: 'Reconciliation', exact: true }).click();
  await expect(operations).toContainText('MATCHED');
  expect(errors).toEqual([]);
});

test('risk-hold recovery is accessible as a separate engineering workflow', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('advance-net')).toHaveText('$195.00');
  await page.getByRole('button', { name: 'Scenario workbench' }).click();
  await page.locator('#start').click();
  await page.locator('#run-all').click();
  await expect(page.locator('#run-status')).toContainText('COMPLETE');
  await page.locator('#workbench').getByRole('tab', { name: 'Bank & outbox' }).click();
  await expect(page.locator('#evidence-body')).toContainText('1 POST · 1 lookup');
  await expect(page.locator('#evidence-body')).toContainText('SETTLED');
  await page.getByRole('button', { name: 'Finance operations' }).click();
  await expect(page.locator('#operations')).toBeVisible();
});

test('public data remains attributed and product views fit the viewport', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('advance-net')).toHaveText('$195.00');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.getByRole('button', { name: 'Public data replay' }).click();
  await expect(page.locator('#dataset-attribution')).toContainText('CC BY 4.0');
  await expect(page.locator('#dataset-date option')).toHaveCount(305);
  await page.locator('#dataset-date').selectOption('2011-01-10');
  await page.locator('#replay-data').click();
  await expect(page.locator('#workbench')).toBeVisible();
  await page.locator('#run-all').click();
  await expect(page.locator('#run-status')).toContainText('COMPLETE');
});
