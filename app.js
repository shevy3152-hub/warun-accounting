const STORAGE_KEY = "warun-kaikei-v1";

const paymentLabels = {
  cash: "現金",
  card: "クレジットカード",
  qr: "QR決済",
  accountsReceivable: "売掛",
  other: "その他",
  bank: "銀行振込",
  payable: "買掛"
};

const defaultData = {
  store: {
    storeName: "小規模飲食店",
    ownerName: "",
    address: "",
    accountantNote: ""
  },
  dailyReports: [],
  expenses: []
};

let state = loadState();
let currentMonth = new Date().toISOString().slice(0, 7);

const yen = new Intl.NumberFormat("ja-JP", {
  style: "currency",
  currency: "JPY",
  maximumFractionDigits: 0
});

const nodes = {
  periodMonth: document.querySelector("#periodMonth"),
  viewTitle: document.querySelector("#viewTitle"),
  navItems: document.querySelectorAll(".nav-item"),
  views: document.querySelectorAll(".view"),
  dailyForm: document.querySelector("#dailyForm"),
  expenseForm: document.querySelector("#expenseForm"),
  settingsForm: document.querySelector("#settingsForm"),
  dailyRows: document.querySelector("#dailyRows"),
  receiptList: document.querySelector("#receiptList"),
  recentReports: document.querySelector("#recentReports"),
  recentExpenses: document.querySelector("#recentExpenses"),
  submitChecklist: document.querySelector("#submitChecklist")
};

function loadState() {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (!saved) return structuredClone(defaultData);
  try {
    return { ...structuredClone(defaultData), ...JSON.parse(saved) };
  } catch {
    return structuredClone(defaultData);
  }
}

function saveState() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

function toNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function reportTotal(report) {
  return Object.values(report.sales || {}).reduce((sum, value) => sum + toNumber(value), 0);
}

function setText(selector, value) {
  const node = document.querySelector(selector);
  if (node) node.textContent = value;
}

function sumSales(reports, key) {
  return reports.reduce((sum, report) => sum + toNumber(report.sales?.[key]), 0);
}

function sumExpenses(expenses, predicate) {
  return expenses
    .filter(predicate)
    .reduce((sum, expense) => sum + toNumber(expense.amount), 0);
}

function monthItems(items) {
  return items.filter((item) => item.date?.startsWith(currentMonth));
}

function sortByDateDesc(items) {
  return [...items].sort((a, b) => b.date.localeCompare(a.date));
}

function setDefaultDates() {
  const today = new Date().toISOString().slice(0, 10);
  nodes.periodMonth.value = currentMonth;
  nodes.dailyForm.elements.date.value = today;
  nodes.expenseForm.elements.date.value = today;
}

function switchView(viewId) {
  nodes.views.forEach((view) => view.classList.toggle("active-view", view.id === viewId));
  nodes.navItems.forEach((item) => item.classList.toggle("active", item.dataset.view === viewId));
  const active = document.querySelector(`[data-view="${viewId}"] span:last-child`);
  nodes.viewTitle.textContent = active ? active.textContent : "ダッシュボード";
}

function emptyState(message) {
  const template = document.querySelector("#emptyTemplate");
  const fragment = template.content.cloneNode(true);
  fragment.querySelector("p").textContent = message;
  return fragment;
}

function render() {
  const reports = monthItems(state.dailyReports);
  const expenses = monthItems(state.expenses);
  const salesTotal = reports.reduce((sum, report) => sum + reportTotal(report), 0);
  const expenseTotal = expenses.reduce((sum, expense) => sum + toNumber(expense.amount), 0);
  const missingReceipts = expenses.filter((expense) => !expense.receiptImage).length;
  const cashSales = sumSales(reports, "cash");
  const cardSales = sumSales(reports, "card");
  const qrSales = sumSales(reports, "qr");
  const arSales = sumSales(reports, "accountsReceivable");
  const cashExpenses = sumExpenses(expenses, (expense) => expense.paymentMethod === "cash");
  const foodExpense = sumExpenses(expenses, (expense) => expense.category === "仕入");
  const supplyExpense = sumExpenses(expenses, (expense) => expense.category === "消耗品費");
  const fixedExpense = sumExpenses(expenses, (expense) => ["家賃", "水道光熱費", "通信費"].includes(expense.category));
  const otherExpense = Math.max(0, expenseTotal - foodExpense - supplyExpense - fixedExpense);
  const latestReport = sortByDateDesc(reports)[0];
  const openingCash = toNumber(latestReport?.openingCash);
  const recordedClosingCash = toNumber(latestReport?.closingCash);
  const closingCash = recordedClosingCash || openingCash + cashSales - cashExpenses;

  setText("#metricSales", yen.format(salesTotal));
  setText("#metricExpenses", yen.format(expenseTotal));
  setText("#metricBalance", yen.format(salesTotal - expenseTotal));
  setText("#metricMissing", `${missingReceipts}件`);
  setText("#dailyCount", `${reports.length}件`);
  setText("#expenseCount", `${expenses.length}件`);
  setText("#submitPeriod", currentMonth);
  setText("#metricCashSales", yen.format(cashSales));
  setText("#metricCardSales", yen.format(cardSales));
  setText("#metricQrSales", yen.format(qrSales));
  setText("#metricArSales", yen.format(arSales));
  setText("#metricFoodExpense", yen.format(foodExpense));
  setText("#metricSupplyExpense", yen.format(supplyExpense));
  setText("#metricFixedExpense", yen.format(fixedExpense));
  setText("#metricOtherExpense", yen.format(otherExpense));
  setText("#metricOpeningCash", yen.format(openingCash));
  setText("#metricCashIn", yen.format(cashSales));
  setText("#metricCashOut", yen.format(cashExpenses));
  setText("#metricClosingCash", yen.format(closingCash));
  setText("#sideSales", yen.format(salesTotal));
  setText("#sideProfit", yen.format(salesTotal - expenseTotal));
  setText("#sideCash", yen.format(closingCash));

  renderDailyRows(reports);
  renderReceiptList(expenses);
  renderTimeline(nodes.recentReports, reports, "日報がまだありません", (report) => ({
    date: report.date,
    title: `${yen.format(reportTotal(report))} / ${report.guestCount || 0}名 / ${report.groupCount || 0}組`,
    meta: report.memo || "メモなし"
  }));
  renderTimeline(nodes.recentExpenses, expenses, "支出がまだありません", (expense) => ({
    date: expense.date,
    title: `${yen.format(expense.amount)} / ${expense.vendor}`,
    meta: `${expense.category}・${paymentLabels[expense.paymentMethod] || expense.paymentMethod}`
  }));
  renderChecklist(reports, expenses, salesTotal, expenseTotal, missingReceipts);
  fillSettings();
}

function renderDailyRows(reports) {
  nodes.dailyRows.innerHTML = "";
  if (!reports.length) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 6;
    cell.append(emptyState("対象月の日報がまだありません"));
    row.append(cell);
    nodes.dailyRows.append(row);
    return;
  }

  sortByDateDesc(reports).forEach((report) => {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>${report.date}</td>
      <td>${yen.format(reportTotal(report))}</td>
      <td>${yen.format(report.sales.cash || 0)}</td>
      <td>${report.guestCount || 0}名 / ${report.groupCount || 0}組</td>
      <td>${escapeHtml(report.memo || "")}</td>
      <td><button class="icon-button" type="button" title="削除" data-delete-report="${report.id}">×</button></td>
    `;
    nodes.dailyRows.append(row);
  });
}

function renderReceiptList(expenses) {
  nodes.receiptList.innerHTML = "";
  if (!expenses.length) {
    nodes.receiptList.append(emptyState("対象月の支出がまだありません"));
    return;
  }

  sortByDateDesc(expenses).forEach((expense) => {
    const item = document.createElement("article");
    item.className = "receipt-item";
    const preview = expense.receiptImage
      ? `<img src="${expense.receiptImage}" alt="${escapeHtml(expense.vendor)}のレシート">`
      : "画像なし";
    item.innerHTML = `
      <div class="receipt-preview">${preview}</div>
      <div class="receipt-body">
        <div class="receipt-meta">
          <strong>${escapeHtml(expense.vendor)}</strong>
          <button class="icon-button" type="button" title="削除" data-delete-expense="${expense.id}">×</button>
        </div>
        <p>${expense.date}・${expense.category}・${paymentLabels[expense.paymentMethod] || expense.paymentMethod}</p>
        <div class="receipt-meta">
          <strong>${yen.format(expense.amount)}</strong>
          <span class="count-label">税区分 ${expense.taxRate}%</span>
        </div>
      </div>
    `;
    nodes.receiptList.append(item);
  });
}

function renderTimeline(container, items, emptyMessage, mapper) {
  container.innerHTML = "";
  if (!items.length) {
    container.append(emptyState(emptyMessage));
    return;
  }

  sortByDateDesc(items).slice(0, 5).forEach((item) => {
    const view = mapper(item);
    const row = document.createElement("div");
    row.className = "timeline-item";
    row.innerHTML = `
      <time>${view.date}</time>
      <span>${escapeHtml(view.meta)}</span>
      <strong>${escapeHtml(view.title)}</strong>
    `;
    container.append(row);
  });
}

function renderChecklist(reports, expenses, salesTotal, expenseTotal, missingReceipts) {
  const checks = [
    {
      label: "対象月の日報が登録されている",
      value: `${reports.length}日分`,
      ok: reports.length > 0
    },
    {
      label: "売上合計を確認できる",
      value: yen.format(salesTotal),
      ok: salesTotal > 0
    },
    {
      label: "支出とレシートの対応を確認できる",
      value: missingReceipts ? `未添付 ${missingReceipts}件` : `${expenses.length}件すべて添付済み`,
      ok: expenses.length > 0 && missingReceipts === 0
    },
    {
      label: "支出合計を確認できる",
      value: yen.format(expenseTotal),
      ok: expenseTotal > 0
    },
    {
      label: "店舗情報が入力されている",
      value: state.store.storeName || "未設定",
      ok: Boolean(state.store.storeName)
    }
  ];

  nodes.submitChecklist.innerHTML = "";
  checks.forEach((check) => {
    const item = document.createElement("div");
    item.className = "check-item";
    item.innerHTML = `
      <span class="status-dot ${check.ok ? "ok" : ""}" aria-hidden="true"></span>
      <span>${check.label}</span>
      <strong>${check.value}</strong>
    `;
    nodes.submitChecklist.append(item);
  });
}

function fillSettings() {
  Object.entries(state.store).forEach(([key, value]) => {
    if (nodes.settingsForm.elements[key] && nodes.settingsForm.elements[key].value !== value) {
      nodes.settingsForm.elements[key].value = value;
    }
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function formToReport(form) {
  const values = Object.fromEntries(new FormData(form).entries());
  return {
    id: crypto.randomUUID(),
    date: values.date,
    guestCount: toNumber(values.guestCount),
    groupCount: toNumber(values.groupCount),
    sales: {
      cash: toNumber(values.cash),
      card: toNumber(values.card),
      qr: toNumber(values.qr),
      accountsReceivable: toNumber(values.accountsReceivable),
      other: 0
    },
    openingCash: toNumber(values.openingCash),
    closingCash: toNumber(values.closingCash),
    memo: values.memo.trim()
  };
}

async function formToExpense(form) {
  const values = Object.fromEntries(new FormData(form).entries());
  const file = form.elements.receiptImage.files[0];
  return {
    id: crypto.randomUUID(),
    date: values.date,
    vendor: values.vendor.trim(),
    category: values.category,
    amount: toNumber(values.amount),
    paymentMethod: values.paymentMethod,
    taxRate: values.taxRate,
    receiptImage: file ? await readFileAsDataUrl(file) : "",
    memo: values.memo.trim()
  };
}

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

function download(filename, content, type) {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

function exportCsv() {
  const reports = monthItems(state.dailyReports);
  const expenses = monthItems(state.expenses);
  const rows = [
    ["種別", "日付", "相手先", "科目", "来客数", "組数", "現金", "カード", "QR", "売掛", "金額", "税区分", "メモ"]
  ];

  sortByDateDesc(reports).forEach((report) => {
    rows.push([
      "売上",
      report.date,
      state.store.storeName,
      "日報売上",
      report.guestCount || 0,
      report.groupCount || 0,
      report.sales.cash || 0,
      report.sales.card || 0,
      report.sales.qr || 0,
      report.sales.accountsReceivable || 0,
      reportTotal(report),
      "",
      report.memo || ""
    ]);
  });

  sortByDateDesc(expenses).forEach((expense) => {
    rows.push([
      "支出",
      expense.date,
      expense.vendor,
      expense.category,
      "",
      "",
      expense.paymentMethod === "cash" ? expense.amount : "",
      expense.paymentMethod === "card" ? expense.amount : "",
      expense.paymentMethod === "qr" ? expense.amount : "",
      expense.paymentMethod === "payable" ? expense.amount : "",
      expense.amount,
      `${expense.taxRate}%`,
      expense.memo || ""
    ]);
  });

  const csv = rows.map((row) => row.map(csvCell).join(",")).join("\n");
  download(`warun-kaikei-${currentMonth}.csv`, `\uFEFF${csv}`, "text/csv;charset=utf-8");
}

function csvCell(value) {
  return `"${String(value).replaceAll('"', '""')}"`;
}

function importJson(file) {
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const imported = JSON.parse(reader.result);
      state = { ...structuredClone(defaultData), ...imported };
      saveState();
      render();
      alert("バックアップを読み込みました。");
    } catch {
      alert("JSONを読み込めませんでした。");
    }
  };
  reader.readAsText(file);
}

nodes.navItems.forEach((item) => {
  item.addEventListener("click", () => switchView(item.dataset.view));
});

document.querySelectorAll("[data-view-jump]").forEach((button) => {
  button.addEventListener("click", () => switchView(button.dataset.viewJump));
});

nodes.periodMonth.addEventListener("change", (event) => {
  currentMonth = event.target.value || currentMonth;
  render();
});

nodes.dailyForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const report = formToReport(nodes.dailyForm);
  state.dailyReports = state.dailyReports.filter((item) => item.date !== report.date);
  state.dailyReports.push(report);
  saveState();
  nodes.dailyForm.reset();
  setDefaultDates();
  render();
});

nodes.expenseForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const expense = await formToExpense(nodes.expenseForm);
  state.expenses.push(expense);
  saveState();
  nodes.expenseForm.reset();
  setDefaultDates();
  render();
});

nodes.settingsForm.addEventListener("submit", (event) => {
  event.preventDefault();
  state.store = Object.fromEntries(new FormData(nodes.settingsForm).entries());
  saveState();
  render();
});

document.querySelector("#clearDailyForm").addEventListener("click", () => {
  nodes.dailyForm.reset();
  setDefaultDates();
});

document.querySelector("#clearExpenseForm").addEventListener("click", () => {
  nodes.expenseForm.reset();
  setDefaultDates();
});

document.body.addEventListener("click", (event) => {
  const reportId = event.target.dataset.deleteReport;
  const expenseId = event.target.dataset.deleteExpense;
  if (reportId && confirm("この日報を削除しますか。")) {
    state.dailyReports = state.dailyReports.filter((report) => report.id !== reportId);
    saveState();
    render();
  }
  if (expenseId && confirm("この支出を削除しますか。")) {
    state.expenses = state.expenses.filter((expense) => expense.id !== expenseId);
    saveState();
    render();
  }
});

document.querySelector("#exportCsv").addEventListener("click", exportCsv);

document.querySelector("#exportJson").addEventListener("click", () => {
  download(`warun-kaikei-backup-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(state, null, 2), "application/json");
});

document.querySelector("#importJson").addEventListener("change", (event) => {
  const file = event.target.files[0];
  if (file) importJson(file);
});

document.querySelector("#printPacket").addEventListener("click", () => window.print());

setDefaultDates();
render();
