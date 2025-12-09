// 全局变量
let currentSection = 'dashboard';
let plans = [];
let codes = [];
let licenses = [];

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', function() {
    loadDashboard();
});

// 显示不同的管理页面
function showSection(section) {
    // 隐藏所有页面
    document.querySelectorAll('.section').forEach(el => el.style.display = 'none');
    
    // 移除所有导航链接的活动状态
    document.querySelectorAll('.nav-link').forEach(el => el.classList.remove('active'));
    
    // 显示目标页面并设置导航链接为活动状态
    document.getElementById(section).style.display = 'block';
    event.target.classList.add('active');
    
    currentSection = section;
    
    // 根据页面加载对应数据
    switch(section) {
        case 'dashboard':
            loadDashboard();
            break;
        case 'plans':
            loadPlans();
            break;
        case 'codes':
            loadCodes();
            break;
        case 'licenses':
            loadLicenses();
            break;
        case 'stats':
            loadStats();
            break;
        case 'sources':
            loadSources();
            break;
        case 'audit':
            loadAuditLogs();
            break;
    }
}

// 加载仪表板数据
async function loadDashboard() {
    try {
        const [plansRes, codesRes, licensesRes, statsRes] = await Promise.all([
            fetch('/api/admin/plans'),
            fetch('/api/admin/codes'),
            fetch('/api/admin/licenses'),
            fetch('/api/admin/stats/downloads')
        ]);
        
        const plansData = await plansRes.json();
        const codesData = await codesRes.json();
        const licensesData = await licensesRes.json();
        const statsData = await statsRes.json();
        
        document.getElementById('totalPlans').textContent = plansData.length;
        document.getElementById('totalCodes').textContent = codesData.length;
        document.getElementById('totalLicenses').textContent = licensesData.length;
        document.getElementById('todayDownloads').textContent = statsData.today_downloads || 0;
        
    } catch (error) {
        console.error('加载仪表板数据失败:', error);
        showAlert('加载仪表板数据失败', 'danger');
    }
}

// 加载套餐数据
async function loadPlans() {
    try {
        const response = await fetch('/api/admin/plans');
        plans = await response.json();
        
        const tbody = document.getElementById('plansTable');
        tbody.innerHTML = '';
        
        plans.forEach(plan => {
            const row = document.createElement('tr');
            row.innerHTML = `
                <td>${plan.id}</td>
                <td>${plan.name}</td>
                <td>${plan.durationHours}</td>
                <td>${plan.initQuota}</td>
                <td>${plan.allowGrace ? '<span class="badge bg-success">是</span>' : '<span class="badge bg-secondary">否</span>'}</td>
                <td>${formatDateTime(plan.createdAt)}</td>
                <td>
                    <button class="btn btn-sm btn-danger" onclick="deletePlan(${plan.id})">删除</button>
                </td>
            `;
            tbody.appendChild(row);
        });
        
        // 更新批量创建激活码的套餐选择
        updatePlanSelect();
        
    } catch (error) {
        console.error('加载套餐数据失败:', error);
        showAlert('加载套餐数据失败', 'danger');
    }
}

// 加载激活码数据
async function loadCodes() {
    try {
        const response = await fetch('/api/admin/codes');
        codes = await response.json();
        
        const tbody = document.getElementById('codesTable');
        tbody.innerHTML = '';
        
        codes.forEach(code => {
            const row = document.createElement('tr');
            const statusBadge = getStatusBadge(code.status);
            
            row.innerHTML = `
                <td><code>${code.code}</code></td>
                <td>${code.planName || 'Unknown'}</td>
                <td>${code.issueCount}/${code.issueLimit}</td>
                <td>${formatDateTime(code.expAt)}</td>
                <td>${statusBadge}</td>
                <td>${code.note || '-'}</td>
                <td>
                    <button class="btn btn-sm btn-warning me-1" onclick="freezeCode('${code.code}')">冻结</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteCode('${code.code}')">删除</button>
                </td>
            `;
            tbody.appendChild(row);
        });
        
    } catch (error) {
        console.error('加载激活码数据失败:', error);
        showAlert('加载激活码数据失败', 'danger');
    }
}

// 加载许可证数据
async function loadLicenses() {
    try {
        const response = await fetch('/api/admin/licenses');
        licenses = await response.json();
        
        const tbody = document.getElementById('licensesTable');
        tbody.innerHTML = '';
        
        licenses.forEach(license => {
            const row = document.createElement('tr');
            const statusBadge = getStatusBadge(license.status);
            
            row.innerHTML = `
                <td>${license.id}</td>
                <td><code>${license.code}</code></td>
                <td>${license.sub}</td>
                <td><small>${license.hwid.substring(0, 16)}...</small></td>
                <td>${license.planName || 'Unknown'}</td>
                <td>${formatDateTime(license.validFrom)} ~ ${formatDateTime(license.validTo)}</td>
                <td>${license.downloadQuotaRemaining}/${license.downloadQuotaTotal}</td>
                <td>${statusBadge}</td>
                <td>
                    <div class="btn-group btn-group-sm">
                        <button class="btn btn-info" onclick="renewLicense(${license.id})">续期</button>
                        <button class="btn btn-success" onclick="addQuota(${license.id})">加次</button>
                        <button class="btn btn-danger" onclick="revokeLicense(${license.id})">吊销</button>
                    </div>
                </td>
            `;
            tbody.appendChild(row);
        });
        
    } catch (error) {
        console.error('加载许可证数据失败:', error);
        showAlert('加载许可证数据失败', 'danger');
    }
}

// 加载下载统计
async function loadStats() {
    try {
        const response = await fetch('/api/admin/stats/downloads');
        const stats = await response.json();
        
        document.getElementById('statsToday').textContent = stats.today_downloads || 0;
        
        const tbody = document.getElementById('recentDownloads');
        tbody.innerHTML = '';
        
        if (stats.recent_downloads) {
            stats.recent_downloads.forEach(event => {
                const row = document.createElement('tr');
                row.innerHTML = `
                    <td>${event.licenseId}</td>
                    <td>${event.fileId}</td>
                    <td>${event.ok ? '<span class="badge bg-success">成功</span>' : '<span class="badge bg-danger">失败</span>'}</td>
                    <td>${event.deducted ? '<span class="badge bg-warning">扣除</span>' : '<span class="badge bg-secondary">未扣</span>'}</td>
                    <td>${formatDateTime(event.createdAt)}</td>
                `;
                tbody.appendChild(row);
            });
        }
        
    } catch (error) {
        console.error('加载统计数据失败:', error);
        showAlert('加载统计数据失败', 'danger');
    }
}

// 加载审计日志（支持分页）
let currentAuditPage = 1;
let currentAuditPageSize = 50;

async function loadAuditLogs(page = 1, pageSize = 50) {
    try {
        currentAuditPage = page;
        currentAuditPageSize = pageSize;
        
        const response = await fetch(`/api/admin/audit-logs?page=${page}&pageSize=${pageSize}`);
        const data = await response.json();
        
        const tbody = document.getElementById('auditTable');
        tbody.innerHTML = '';
        
        // 处理新的分页数据格式
        const logs = data.logs || [];
        
        if (logs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="text-center text-muted">暂无审计日志</td></tr>';
        } else {
            logs.forEach(log => {
                const row = document.createElement('tr');
                row.innerHTML = `
                    <td>${log.actor}</td>
                    <td><span class="badge bg-primary">${log.action}</span></td>
                    <td><code>${log.target || '-'}</code></td>
                    <td>${log.details || '-'}</td>
                    <td>${formatDateTime(log.createdAt)}</td>
                `;
                tbody.appendChild(row);
            });
        }
        
        // 更新分页信息和控件
        updateAuditPagination(data);
        
    } catch (error) {
        console.error('加载审计日志失败:', error);
        showAlert('加载审计日志失败', 'danger');
    }
}

// 更新审计日志分页控件
function updateAuditPagination(data) {
    const paginationInfo = document.getElementById('auditPaginationInfo');
    const paginationControls = document.getElementById('auditPaginationControls');
    
    if (!paginationInfo || !paginationControls) return;
    
    // 更新分页信息
    paginationInfo.textContent = `共 ${data.total || 0} 条记录，第 ${data.page || 1} / ${data.totalPages || 1} 页`;
    
    // 生成分页按钮
    paginationControls.innerHTML = '';
    
    const totalPages = data.totalPages || 1;
    const currentPage = data.page || 1;
    
    // 上一页按钮
    const prevBtn = document.createElement('button');
    prevBtn.className = 'btn btn-sm btn-outline-primary me-1';
    prevBtn.innerHTML = '<i class="bi bi-chevron-left"></i>';
    prevBtn.disabled = currentPage === 1;
    prevBtn.onclick = () => loadAuditLogs(currentPage - 1, currentAuditPageSize);
    paginationControls.appendChild(prevBtn);
    
    // 页码按钮（显示当前页前后2页）
    let startPage = Math.max(1, currentPage - 2);
    let endPage = Math.min(totalPages, currentPage + 2);
    
    if (startPage > 1) {
        const firstBtn = createPageButton(1);
        paginationControls.appendChild(firstBtn);
        if (startPage > 2) {
            const dots = document.createElement('span');
            dots.className = 'mx-1';
            dots.textContent = '...';
            paginationControls.appendChild(dots);
        }
    }
    
    for (let i = startPage; i <= endPage; i++) {
        const pageBtn = createPageButton(i);
        if (i === currentPage) {
            pageBtn.classList.remove('btn-outline-primary');
            pageBtn.classList.add('btn-primary');
        }
        paginationControls.appendChild(pageBtn);
    }
    
    if (endPage < totalPages) {
        if (endPage < totalPages - 1) {
            const dots = document.createElement('span');
            dots.className = 'mx-1';
            dots.textContent = '...';
            paginationControls.appendChild(dots);
        }
        const lastBtn = createPageButton(totalPages);
        paginationControls.appendChild(lastBtn);
    }
    
    // 下一页按钮
    const nextBtn = document.createElement('button');
    nextBtn.className = 'btn btn-sm btn-outline-primary ms-1';
    nextBtn.innerHTML = '<i class="bi bi-chevron-right"></i>';
    nextBtn.disabled = currentPage === totalPages;
    nextBtn.onclick = () => loadAuditLogs(currentPage + 1, currentAuditPageSize);
    paginationControls.appendChild(nextBtn);
}

// 创建页码按钮
function createPageButton(pageNumber) {
    const btn = document.createElement('button');
    btn.className = 'btn btn-sm btn-outline-primary me-1';
    btn.textContent = pageNumber;
    btn.onclick = () => loadAuditLogs(pageNumber, currentAuditPageSize);
    return btn;
}

// 清空所有审计日志
async function clearAllAuditLogs() {
    if (!confirm('确定要清空所有审计日志吗？此操作不可恢复！')) {
        return;
    }
    
    try {
        const response = await fetch('/api/admin/audit-logs', {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (result.ok) {
            showAlert(result.message, 'success');
            loadAuditLogs(1, currentAuditPageSize);
        } else {
            showAlert(result.error || '清空失败', 'danger');
        }
    } catch (error) {
        console.error('清空审计日志失败:', error);
        showAlert('清空审计日志失败', 'danger');
    }
}

// 清理旧日志
async function cleanupOldAuditLogs() {
    const days = prompt('清理多少天之前的审计日志？', '30');
    if (!days) return;
    
    const daysNumber = parseInt(days);
    if (isNaN(daysNumber) || daysNumber <= 0) {
        showAlert('请输入有效的天数', 'danger');
        return;
    }
    
    if (!confirm(`确定要清理 ${daysNumber} 天之前的审计日志吗？`)) {
        return;
    }
    
    try {
        const response = await fetch(`/api/admin/audit-logs/cleanup?daysAgo=${daysNumber}`, {
            method: 'DELETE'
        });
        
        const result = await response.json();
        
        if (result.ok) {
            showAlert(result.message, 'success');
            loadAuditLogs(1, currentAuditPageSize);
        } else {
            showAlert(result.error || '清理失败', 'danger');
        }
    } catch (error) {
        console.error('清理审计日志失败:', error);
        showAlert('清理审计日志失败', 'danger');
    }
}

// 显示创建套餐模态框
function showCreatePlanModal() {
    new bootstrap.Modal(document.getElementById('createPlanModal')).show();
}

// 创建套餐
async function createPlan() {
    const form = document.getElementById('createPlanForm');
    const formData = new FormData(form);
    
    const data = {
        name: formData.get('name'),
        duration_hours: parseInt(formData.get('duration_hours')),
        init_quota: parseInt(formData.get('init_quota')),
        allow_grace: formData.get('allow_grace') === 'on',
        features: formData.get('features')
    };
    
    try {
        const response = await fetch('/api/admin/plans', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(data)
        });
        
        const result = await response.json();
        
        if (result.ok) {
            showAlert('套餐创建成功', 'success');
            bootstrap.Modal.getInstance(document.getElementById('createPlanModal')).hide();
            form.reset();
            loadPlans();
        } else {
            showAlert('套餐创建失败: ' + result.error, 'danger');
        }
        
    } catch (error) {
        console.error('创建套餐失败:', error);
        showAlert('创建套餐失败', 'danger');
    }
}

// 删除套餐
async function deletePlan(id) {
    if (!confirm('确定要删除这个套餐吗？')) return;
    
    try {
        const response = await fetch(`/api/admin/plans/${id}`, {
            method: 'DELETE'
        });
        
        if (response.ok) {
            showAlert('套餐删除成功', 'success');
            loadPlans();
        } else {
            showAlert('套餐删除失败', 'danger');
        }
        
    } catch (error) {
        console.error('删除套餐失败:', error);
        showAlert('删除套餐失败', 'danger');
    }
}

// 显示批量创建激活码模态框
function showBatchCreateCodeModal() {
    updatePlanSelect();
    
    // 设置默认过期时间为30天后
    const expInput = document.querySelector('#batchCreateCodeModal input[name="exp_at"]');
    const defaultExp = new Date();
    defaultExp.setDate(defaultExp.getDate() + 30);
    expInput.value = defaultExp.toISOString().slice(0, 16);
    
    // 初始化默认套餐
    initializeDefaultPlans();
    
    new bootstrap.Modal(document.getElementById('batchCreateCodeModal')).show();
}

// 批量创建激活码
async function batchCreateCodes() {
    const form = document.getElementById('batchCreateCodeForm');
    const formData = new FormData(form);
    
    const cardType = formData.get('card_type');
    let planId;
    
    // 根据卡类型确定套餐ID
    if (cardType === 'week' || cardType === 'month') {
        const planName = cardType === 'week' ? '周卡' : '月卡';
        const plan = plans.find(p => p.name === planName);
        if (!plan) {
            showAlert(`未找到${planName}套餐，请先创建`, 'danger');
            return;
        }
        planId = plan.id;
    } else if (cardType === 'custom') {
        planId = parseInt(formData.get('plan_id'));
        if (!planId) {
            showAlert('请选择套餐', 'danger');
            return;
        }
    } else {
        showAlert('请选择卡类型', 'danger');
        return;
    }
    
    const data = {
        plan_id: planId,
        issue_limit: parseInt(formData.get('issue_limit')),
        exp_at: formData.get('exp_at'),
        note: formData.get('note'),
        count: parseInt(formData.get('count'))
    };
    
    try {
        const response = await fetch('/api/admin/codes/batch', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(data)
        });
        
        const result = await response.json();
        
        if (result.ok) {
            showAlert(`成功创建 ${result.codes.length} 个激活码`, 'success');
            bootstrap.Modal.getInstance(document.getElementById('batchCreateCodeModal')).hide();
            form.reset();
            loadCodes();
        } else {
            showAlert('批量创建失败: ' + result.error, 'danger');
        }
        
    } catch (error) {
        console.error('批量创建激活码失败:', error);
        showAlert('批量创建激活码失败', 'danger');
    }
}

// 冻结激活码
async function freezeCode(code) {
    await updateCodeStatus(code, 'frozen');
}

// 删除激活码（连带删除对应的许可证）
async function deleteCode(code) {
    if (!confirm('确定要删除这个激活码吗？\n注意：这将同时删除该激活码对应的所有许可证，此操作不可恢复！')) return;
    
    try {
        const response = await fetch(`/api/admin/codes/${code}`, {
            method: 'DELETE',
            headers: {
                'Content-Type': 'application/json'
            }
        });
        
        const result = await response.json();
        
        if (response.ok) {
            showAlert(`激活码删除成功！${result.deletedLicenses > 0 ? `同时删除了 ${result.deletedLicenses} 个相关许可证` : ''}`, 'success');
            loadCodes(); // 重新加载激活码列表
            loadLicenses(); // 重新加载许可证列表
        } else {
            showAlert(result.error || '删除激活码失败', 'danger');
        }
        
    } catch (error) {
        console.error('删除激活码失败:', error);
        showAlert('删除激活码失败', 'danger');
    }
}

// 更新激活码状态
async function updateCodeStatus(code, status) {
    try {
        const response = await fetch(`/api/admin/codes/${code}/status`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ status })
        });
        
        if (response.ok) {
            showAlert(`激活码状态更新为 ${status}`, 'success');
            loadCodes();
        } else {
            showAlert('状态更新失败', 'danger');
        }
        
    } catch (error) {
        console.error('更新激活码状态失败:', error);
        showAlert('状态更新失败', 'danger');
    }
}

// 续期许可证
async function renewLicense(id) {
    const days = prompt('请输入要延长的天数:', '7');
    if (!days) return;
    
    const resetQuota = confirm('是否重置下载额度？');
    
    try {
        const response = await fetch(`/api/admin/licenses/${id}/renew`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                extra_days: parseInt(days),
                reset_quota: resetQuota
            })
        });
        
        if (response.ok) {
            showAlert('许可证续期成功', 'success');
            loadLicenses();
        } else {
            showAlert('许可证续期失败', 'danger');
        }
        
    } catch (error) {
        console.error('续期许可证失败:', error);
        showAlert('续期许可证失败', 'danger');
    }
}

// 增加下载额度
async function addQuota(id) {
    const extra = prompt('请输入要增加的下载次数:', '5');
    if (!extra) return;
    
    try {
        const response = await fetch(`/api/admin/licenses/${id}/add-quota`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                extra: parseInt(extra)
            })
        });
        
        if (response.ok) {
            showAlert('下载额度增加成功', 'success');
            loadLicenses();
        } else {
            showAlert('增加额度失败', 'danger');
        }
        
    } catch (error) {
        console.error('增加下载额度失败:', error);
        showAlert('增加额度失败', 'danger');
    }
}

// 吊销许可证
async function revokeLicense(id) {
    const reason = prompt('请输入吊销原因:', '违规使用');
    if (!reason) return;
    
    try {
        const response = await fetch(`/api/admin/licenses/${id}/revoke`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ reason })
        });
        
        if (response.ok) {
            showAlert('许可证吊销成功', 'success');
            loadLicenses();
        } else {
            showAlert('许可证吊销失败', 'danger');
        }
        
    } catch (error) {
        console.error('吊销许可证失败:', error);
        showAlert('许可证吊销失败', 'danger');
    }
}

// 更新套餐选择框
function updatePlanSelect() {
    const select = document.querySelector('#batchCreateCodeModal select[name="plan_id"]');
    select.innerHTML = '<option value="">请选择套餐</option>';
    
    plans.forEach(plan => {
        const option = document.createElement('option');
        option.value = plan.id;
        option.textContent = `${plan.name} (${plan.durationHours}h, ${plan.initQuota}次)`;
        select.appendChild(option);
    });
}

// 获取状态徽章
function getStatusBadge(status) {
    switch(status) {
        case 'active':
        case 'ok':
            return '<span class="badge bg-success">正常</span>';
        case 'frozen':
            return '<span class="badge bg-warning">冻结</span>';
        case 'revoked':
            return '<span class="badge bg-danger">吊销</span>';
        case 'expired':
            return '<span class="badge bg-secondary">过期</span>';
        default:
            return '<span class="badge bg-secondary">' + status + '</span>';
    }
}

// 格式化日期时间
function formatDateTime(dateString) {
    if (!dateString) return '-';
    const date = new Date(dateString);
    return date.toLocaleString('zh-CN');
}

// 卡类型设置更新
function updateCardSettings() {
    const cardType = document.querySelector('#batchCreateCodeModal select[name="card_type"]').value;
    const customSettings = document.getElementById('customSettings');
    
    if (cardType === 'custom') {
        customSettings.style.display = 'block';
    } else {
        customSettings.style.display = 'none';
    }
}

// 初始化默认套餐
async function initializeDefaultPlans() {
    try {
        // 检查是否已存在周卡和月卡套餐
        const weekPlan = plans.find(p => p.name === '周卡');
        const monthPlan = plans.find(p => p.name === '月卡');
        
        if (!weekPlan) {
            await createPlanSilent('周卡', 24 * 7, 15, false, 'Weekly plan with 15 downloads');
        }
        
        if (!monthPlan) {
            await createPlanSilent('月卡', 24 * 30, 30, false, 'Monthly plan with 30 downloads');
        }
        
        // 重新加载套餐列表
        await loadPlans();
        
    } catch (error) {
        console.error('初始化默认套餐失败:', error);
    }
}

// 静默创建套餐（不显示提示）
async function createPlanSilent(name, durationHours, initQuota, allowGrace, features) {
    const data = {
        name: name,
        duration_hours: durationHours,
        init_quota: initQuota,
        allow_grace: allowGrace || false,
        features: features
    };
    
    const response = await fetch('/api/admin/plans', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(data)
    });
    
    return await response.json();
}

// 加载源码数据
async function loadSources() {
    try {
        const response = await fetch('/api/sources');
        const data = await response.json();
        
        const tbody = document.getElementById('sourcesTable');
        tbody.innerHTML = '';
        
        if (data.success && data.data) {
            data.data.forEach(source => {
                const row = document.createElement('tr');
                const thumbnailUrl = source.thumbnailUrl || (source.sha256 ? `/api/sources/by-sha/${source.sha256}/thumbnail` : '');
                const downloadUrl = source.artifactUrl || `/d/${source.sha256}`;
                
                row.innerHTML = `
                    <td>
                        <img src="${thumbnailUrl}" class="thumbnail-preview" alt="缩略图" 
                             onerror="this.src='data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iNjAiIGhlaWdodD0iNjAiIHZpZXdCb3g9IjAgMCA2MCA2MCIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHJlY3Qgd2lkdGg9IjYwIiBoZWlnaHQ9IjYwIiBmaWxsPSIjRjVGNUY1Ii8+CjxwYXRoIGQ9Ik0zMCAyMEM0My4yNTQ4IDIwIDQ0IDIwLjc0NTIgNDQgMzRDNDQgNDcuMjU0OCA0My4yNTQ4IDQ4IDMwIDQ4QzE2Ljc0NTIgNDggMTYgNDcuMjU0OCAxNiAzNEMxNiAyMC43NDUyIDE2Ljc0NTIgMjAgMzAgMjBaIiBmaWxsPSIjRTBFMEUwIi8+Cjx0ZXh0IHg9IjMwIiB5PSIzOCIgZm9udC1mYW1pbHk9IkFyaWFsLCBzYW5zLXNlcmlmIiBmb250LXNpemU9IjEyIiBmaWxsPSIjOTk5OTk5IiB0ZXh0LWFuY2hvcj0ibWlkZGxlIj7nvZHlg488L3RleHQ+Cjwvc3ZnPgo='">
                    </td>
                    <td>
                        <div class="source-info">
                            <div class="source-name">${source.name}</div>
                            <div class="source-code">${source.codeName} v${source.version}</div>
                            <small class="text-muted">${source.description || ''}</small>
                        </div>
                    </td>
                    <td>
                        <div>${source.country || '-'}</div>
                        ${source.website ? `<small><a href="${source.website}" target="_blank">${source.website}</a></small>` : ''}
                    </td>
                    <td class="file-size">${formatFileSize(source.fileSize)}</td>
                    <td><span class="status-badge status-${source.status}">${source.status}</span></td>
                    <td>${formatDateTime(source.updateTime)}</td>
                    <td>
                        <div class="btn-group btn-group-sm">
                            <button class="btn btn-info" onclick="editSource('${source.id}')" title="编辑">
                                <i class="bi bi-pencil"></i>
                            </button>
                            <button class="btn btn-success" onclick="window.open('${downloadUrl}')" title="下载">
                                <i class="bi bi-download"></i>
                            </button>
                            <button class="btn btn-warning" onclick="copyDownloadLink('${downloadUrl}')" title="复制链接">
                                <i class="bi bi-link"></i>
                            </button>
                            <button class="btn btn-primary" onclick="viewSourceStats('${source.sha256}')" title="下载统计">
                                <i class="bi bi-bar-chart"></i>
                            </button>
                            <button class="btn btn-danger" onclick="deleteSource('${source.id}')" title="删除">
                                <i class="bi bi-trash"></i>
                            </button>
                        </div>
                    </td>
                `;
                tbody.appendChild(row);
            });
        }
        
    } catch (error) {
        console.error('加载源码数据失败:', error);
        showAlert('加载源码数据失败', 'danger');
    }
}

// 显示添加源码模态框
function showAddSourceModal() {
    new bootstrap.Modal(document.getElementById('addSourceModal')).show();
}

// 添加源码
async function addSource() {
    const form = document.getElementById('addSourceForm');
    const formData = new FormData(form);
    
    try {
        const response = await fetch('/api/sources/upload', {
            method: 'POST',
            body: formData
        });
        
        const result = await response.json();
        
        if (result.success) {
            showAlert('源码上传成功', 'success');
            bootstrap.Modal.getInstance(document.getElementById('addSourceModal')).hide();
            form.reset();
            loadSources();
        } else {
            showAlert('源码上传失败: ' + result.error, 'danger');
        }
        
    } catch (error) {
        console.error('源码上传失败:', error);
        showAlert('源码上传失败', 'danger');
    }
}

// 复制下载链接
function copyDownloadLink(url) {
    const fullUrl = window.location.origin + url;
    navigator.clipboard.writeText(fullUrl).then(() => {
        showAlert('下载链接已复制到剪贴板', 'success');
    }).catch(() => {
        showAlert('复制失败，请手动复制: ' + fullUrl, 'info');
    });
}

// 格式化文件大小
function formatFileSize(bytes) {
    if (!bytes) return '-';
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return Math.round(bytes / Math.pow(1024, i) * 100) / 100 + ' ' + sizes[i];
}

// 删除源码
async function deleteSource(id) {
    if (!confirm('确定要删除这个源码吗？')) return;
    
    try {
        const response = await fetch(`/api/sources/${id}`, {
            method: 'DELETE'
        });
        
        if (response.ok) {
            showAlert('源码删除成功', 'success');
            loadSources();
        } else {
            showAlert('源码删除失败', 'danger');
        }
        
    } catch (error) {
        console.error('删除源码失败:', error);
        showAlert('删除源码失败', 'danger');
    }
}

// 编辑源码（简化实现）
function editSource(id) {
    showAlert('编辑功能开发中...', 'info');
}

// 查看源码下载统计
async function viewSourceStats(sha256) {
    try {
        const response = await fetch(`/api/authorized-download/stats/${sha256}`);
        const result = await response.json();
        
        if (result.ok) {
            showAlert(`源码 "${result.source.name}" 的下载统计：总下载 ${result.stats.totalDownloads} 次`, 'info');
        } else {
            showAlert('获取统计信息失败', 'danger');
        }
        
    } catch (error) {
        console.error('获取源码统计失败:', error);
        showAlert('获取统计信息失败', 'danger');
    }
}

// 显示提示消息
function showAlert(message, type = 'info') {
    const alertDiv = document.createElement('div');
    alertDiv.className = `alert alert-${type} alert-dismissible fade show`;
    alertDiv.innerHTML = `
        ${message}
        <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    `;
    
    const container = document.querySelector('.col-md-9.col-lg-10');
    container.insertBefore(alertDiv, container.firstChild);
    
    // 3秒后自动消失
    setTimeout(() => {
        if (alertDiv.parentNode) {
            alertDiv.remove();
        }
    }, 3000);
}
