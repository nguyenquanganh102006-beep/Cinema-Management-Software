package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.ConcessionInventory;
import com.cinema.cms.entity.ConcessionItem;
import com.cinema.cms.entity.UserRole;
import com.cinema.cms.repository.ConcessionInventoryRepository;
import com.cinema.cms.repository.ConcessionItemRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConcessionService {
    static final int MAX_PAGE_SIZE = 100;
    private final ConcessionItemRepository items;
    private final ConcessionInventoryRepository inventory;

    public ConcessionService(ConcessionItemRepository items, ConcessionInventoryRepository inventory) { this.items = items; this.inventory = inventory; }

    @Transactional(readOnly = true)
    public PageResponse<ConcessionResponse> list(String keyword, String active, String combo, int page, int size, UserRole viewer) {
        QueryParams.checkPaging(page, size, MAX_PAGE_SIZE);
        Boolean requestedActive = QueryParams.bool(active, "active"), requestedCombo = QueryParams.bool(combo, "combo");
        Boolean effectiveActive = viewer == UserRole.MANAGER ? requestedActive : Boolean.TRUE;
        Specification<ConcessionItem> spec = (root, query, cb) -> cb.conjunction();
        if (keyword != null && !keyword.isBlank()) spec = spec.and(nameContains(keyword.strip()));
        if (effectiveActive != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), effectiveActive));
        if (requestedCombo != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("combo"), requestedCombo));
        Page<ConcessionItem> found = items.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
        Map<Integer, String> names = namesOfComponents(found.getContent());
        return PageResponse.from(found, i -> ConcessionResponse.from(i, names));
    }

    @Transactional(readOnly = true)
    public ConcessionResponse get(Integer id, UserRole viewer) {
        ConcessionItem item = items.findById(id).orElseThrow(ConcessionService::notFound);
        if (viewer != UserRole.MANAGER && !item.isActive()) throw notFound();
        return ConcessionResponse.from(item, namesOfComponents(List.of(item)));
    }

    @Transactional
    public ConcessionResponse createItem(ConcessionRequest r) {
        requireFreeName(r.name(), null);
        try {
            ConcessionItem item = items.saveAndFlush(ConcessionItem.simple(r.name(), money(r.price())));
            inventory.saveAndFlush(new ConcessionInventory(item));
            return ConcessionResponse.from(item, Map.of());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public ConcessionResponse updateItem(Integer id, ConcessionRequest r) {
        ConcessionItem item = lockSimple(id);
        requireFreeName(r.name(), id);
        item.update(r.name(), money(r.price()));
        try {
            return ConcessionResponse.from(items.saveAndFlush(item), Map.of());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public ConcessionResponse createCombo(ComboRequest r) {
        requireFreeName(r.name(), null);
        Map<Integer, Integer> components = validateComponents(r.components());
        try {
            ConcessionItem combo = items.saveAndFlush(ConcessionItem.combo(r.name(), money(r.price()), components));
            return ConcessionResponse.from(combo, namesOfComponents(List.of(combo)));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public ConcessionResponse updateCombo(Integer id, ComboRequest r) {
        ConcessionItem combo = items.findByIdForUpdate(id).filter(ConcessionItem::isCombo).orElseThrow(ConcessionService::notFound);
        requireFreeName(r.name(), id);
        Map<Integer, Integer> components = validateComponents(r.components());
        combo.update(r.name(), money(r.price()));
        combo.replaceComponents(components);
        try {
            return ConcessionResponse.from(items.saveAndFlush(combo), namesOfComponents(List.of(combo)));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public ConcessionResponse changeStatus(Integer id, ConcessionStatusRequest r) {
        ConcessionItem item = items.findByIdForUpdate(id).orElseThrow(ConcessionService::notFound);
        if (!r.active() && !item.isCombo() && item.isActive() && items.countActiveCombosContaining(id) > 0) {
            throw new ApiException(409, "CONCESSION_ITEM_IN_ACTIVE_COMBO", "Món đang nằm trong combo đang bán, hãy ngừng combo trước");
        }
        if (r.active() && item.isCombo() && !item.isActive() && items.findAllById(item.getComponents().keySet()).stream().anyMatch(c -> !c.isActive())) {
            throw new ApiException(409, "CONCESSION_COMBO_COMPONENT_INACTIVE", "Combo có món thành phần đang ngừng bán, hãy bật lại món đó trước");
        }
        item.changeActive(r.active());
        return ConcessionResponse.from(item, namesOfComponents(List.of(item)));
    }

    private ConcessionItem lockSimple(Integer id) {
        return items.findByIdForUpdate(id).filter(i -> !i.isCombo()).orElseThrow(ConcessionService::notFound);
    }

    private Map<Integer, Integer> validateComponents(List<ComboRequest.Component> requested) {
        Map<Integer, Integer> components = new LinkedHashMap<>();
        for (ComboRequest.Component c : requested) {
            if (components.put(c.itemId(), c.quantity()) != null) throw invalidCombo("Món " + c.itemId() + " bị lặp trong combo");
        }
        Map<Integer, ConcessionItem> found = items.findAllById(components.keySet()).stream().collect(Collectors.toMap(ConcessionItem::getId, i -> i));
        for (Integer itemId : components.keySet()) {
            ConcessionItem component = found.get(itemId);
            if (component == null) throw invalidCombo("Không tìm thấy món " + itemId);
            if (component.isCombo()) throw invalidCombo("Combo không được chứa combo khác (món " + itemId + ")");
            if (!component.isActive()) throw invalidCombo("Món " + itemId + " đang ngừng bán");
        }
        return components;
    }

    private Map<Integer, String> namesOfComponents(Collection<ConcessionItem> parents) {
        Set<Integer> ids = parents.stream().flatMap(p -> p.getComponents().keySet().stream()).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return items.findAllById(ids).stream().collect(Collectors.toMap(ConcessionItem::getId, ConcessionItem::getName));
    }

    private void requireFreeName(String name, Integer selfId) {
        boolean taken = selfId == null ? items.existsByNameLower(name) : items.existsByNameLowerAndIdNot(name, selfId);
        if (taken) throw nameTaken();
    }
    private static BigDecimal money(BigDecimal price) { return price.setScale(2, RoundingMode.UNNECESSARY); }
    private static Specification<ConcessionItem> nameContains(String keyword) {
        String pattern = "%" + keyword.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\');
    }
    private static RuntimeException translate(DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause().getMessage();
        return detail != null && detail.contains("concession_items_name_key") ? nameTaken() : e;
    }
    private static ApiException nameTaken() { return new ApiException(409, "CONCESSION_NAME_EXISTS", "Tên món hoặc combo đã tồn tại"); }
    private static ApiException invalidCombo(String message) { return new ApiException(400, "CONCESSION_COMBO_INVALID", message); }
    private static ApiException notFound() { return new ApiException(404, "CONCESSION_ITEM_NOT_FOUND", "Không tìm thấy món hoặc combo"); }
}